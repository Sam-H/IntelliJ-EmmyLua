/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tang.intellij.lua.ty

import com.intellij.psi.stubs.StubInputStream
import com.intellij.psi.stubs.StubOutputStream
import com.intellij.util.Processor
import com.tang.intellij.lua.comment.psi.LuaDocFunctionTy
import com.tang.intellij.lua.comment.psi.LuaDocGenericDef
import com.tang.intellij.lua.psi.LuaCommentOwner
import com.tang.intellij.lua.psi.LuaFuncBodyOwner
import com.tang.intellij.lua.psi.LuaParamInfo
import com.tang.intellij.lua.psi.overloads
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.stubs.readParamInfoArray
import com.tang.intellij.lua.stubs.writeParamInfoArray

interface IFunSignature {
    val selfCall: Boolean
    val returnTy: ITy
    val params: Array<LuaParamInfo>
    val displayName: String
    val paramSignature: String
    val tyParameters: List<TyParameter>
    fun substitute(substitutor: ITySubstitutor): IFunSignature
}

fun IFunSignature.getParamTy(index: Int): ITy {
    val info = params.getOrNull(index)
    return info?.ty ?: Ty.UNKNOWN
}

//eg. print(...)
fun IFunSignature.hasVarArgs(): Boolean {
    return params.lastOrNull()?.isVarArgs ?: false
}

fun IFunSignature.isGeneric() = tyParameters.isNotEmpty()

class FunSignature(override val selfCall: Boolean,
                   override val returnTy: ITy,
                   override val params: Array<LuaParamInfo>,
                   override val tyParameters: List<TyParameter> = emptyList()
) : IFunSignature {
    override fun equals(other: Any?): Boolean {
        if (other is IFunSignature) {
            return params.indices.none { params[it] != other.params.getOrNull(it) }
        }
        return false
    }

    override fun hashCode(): Int {
        var code = returnTy.hashCode()
        params.forEach {
            code += it.ty.hashCode() * 31
        }
        return code
    }

    companion object {
        private fun initParams(func: LuaDocFunctionTy): Array<LuaParamInfo> {
            val list = mutableListOf<LuaParamInfo>()
            func.functionParamList.forEach {
                val p = LuaParamInfo()
                p.name = it.id.text
                p.ty = it.ty?.getType() ?: Ty.UNKNOWN
                list.add(p)
            }
            return list.toTypedArray()
        }

        fun create(selfCall: Boolean, functionTy: LuaDocFunctionTy): IFunSignature {
            return FunSignature(selfCall, functionTy.returnType, initParams(functionTy))
        }

        fun serialize(sig: IFunSignature, stream: StubOutputStream) {
            stream.writeBoolean(sig.selfCall)
            Ty.serialize(sig.returnTy, stream)
            stream.writeParamInfoArray(sig.params)
        }

        fun deserialize(stream: StubInputStream): IFunSignature {
            val selfCall = stream.readBoolean()
            val ret = Ty.deserialize(stream)
            val params = stream.readParamInfoArray()
            return FunSignature(selfCall, ret, params)
        }
    }

    override val displayName: String by lazy {
        val paramSB = mutableListOf<String>()
        params.forEach {
            paramSB.add(it.name + ":" + it.ty.displayName)
        }
        "fun(${paramSB.joinToString(", ")}):${returnTy.displayName}"
    }

    override val paramSignature: String get() {
        val list = arrayOfNulls<String>(params.size)
        for (i in params.indices) {
            val lpi = params[i]
            list[i] = lpi.name
        }
        return "(" + list.joinToString(", ") + ")"
    }

    override fun substitute(substitutor: ITySubstitutor): IFunSignature {
        return FunSignature(selfCall, returnTy.substitute(substitutor), params)
    }
}

interface ITyFunction : ITy {
    val mainSignature: IFunSignature
    val signatures: Array<IFunSignature>
}

val ITyFunction.isSelfCall get() = hasFlag(TyFlags.SELF_FUNCTION)

fun ITyFunction.process(processor: Processor<IFunSignature>) {
    if (processor.process(mainSignature)) {
        for (signature in signatures) {
            if (!processor.process(signature))
                break
        }
    }
}

fun ITyFunction.findPerfectSignature(nArgs: Int): IFunSignature {
    var sgi: IFunSignature? = null
    var perfectN = Int.MAX_VALUE
    process(Processor {
        val offset = Math.abs(it.params.size - nArgs)
        if (offset < perfectN) {
            perfectN = offset
            sgi = it
            if (perfectN == 0) return@Processor false
        }
        true
    })
    return sgi ?: mainSignature
}

abstract class TyFunction : Ty(TyKind.Function), ITyFunction {
    override val displayName: String
        get() {
            return mainSignature.displayName
        }

    override fun equals(other: Any?): Boolean {
        if (other is ITyFunction) {
            if (mainSignature != other.mainSignature)
                return false
           return signatures.indices.none { signatures[it] != other.signatures.getOrNull(it) }
        }
        return false
    }

    override fun hashCode(): Int {
        var code = mainSignature.hashCode()
        signatures.forEach {
            code += it.hashCode() * 31
        }
        return code
    }

    override fun subTypeOf(other: ITy, context: SearchContext): Boolean {
        if (super.subTypeOf(other, context) || other == Ty.FUNCTION) return true // Subtype of function primitive.
        if (other is ITyFunction) {
            if (mainSignature == other.mainSignature || other.signatures.any({ sig -> sig == mainSignature})) return true
            return signatures.any({ sig -> sig == other.mainSignature || other.signatures.any({ sig2 -> sig2 == sig})})
        }
        return false
    }

    override fun substitute(substitutor: ITySubstitutor): ITy {
        return substitutor.substitute(this)
    }
}

class TyPsiFunction(private val selfCall: Boolean, val psi: LuaFuncBodyOwner, searchContext: SearchContext, flags: Int = 0) : TyFunction() {
    init {
        this.flags = flags
        if (selfCall) {
            this.flags = this.flags or TyFlags.SELF_FUNCTION
        }
    }

    override val mainSignature: IFunSignature by lazy {
        var returnTy = psi.guessReturnType(searchContext)
        /**
         * todo optimize this bug solution
         * local function test()
         *      return test
         * end
         * -- will crash after type `test`
         */
        if (returnTy is TyPsiFunction && returnTy.psi == psi) {
           returnTy = Ty.UNKNOWN
        }

        val genericDefList = (psi as? LuaCommentOwner)?.comment?.findTags(LuaDocGenericDef::class.java)
        val list = mutableListOf<TyParameter>()
        genericDefList?.forEach { it.name?.let { name -> list.add(TyParameter(name, it.classNameRef?.resolveType())) } }

        FunSignature(selfCall, returnTy, psi.params, list)
    }

    override val signatures: Array<IFunSignature> by lazy {
        psi.overloads
    }
}

class TyDocPsiFunction(func: LuaDocFunctionTy) : TyFunction() {
    private val main = FunSignature.create(false, func)
    override val mainSignature: IFunSignature = main
    override val signatures: Array<IFunSignature> = emptyArray()
}

class TySerializedFunction(override val mainSignature: IFunSignature,
                           override val signatures: Array<IFunSignature>,
                           flags: Int = 0) : TyFunction() {
    init {
        this.flags = flags
    }
}