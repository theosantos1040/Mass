package com.theo.patcher.patcher

import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11n
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.jf.dexlib2.util.ReferenceUtil
import java.io.File

/**
 * Real DEX rewriting via dexlib2 — parses the DEX, replaces target method
 * bodies with a correct minimal implementation, and re-encodes with valid
 * checksums/offsets. Unlike byte-search patching, this never corrupts the DEX.
 */
object DexRewriter {

    data class Report(val patched: List<String>, val count: Int)

    // Instruction/reference markers that identify a runtime signature/integrity check.
    private val SIG_MARKERS = listOf(
        "getPackageInfo",
        "Landroid/content/pm/Signature",
        "signingInfo",
        "getApkContentsSigners",
        "getSigningCertificateHistory",
        "GET_SIGNATURES",
        "GET_SIGNING_CERTIFICATES",
        ";->signatures",
        "PackageManager;->checkSignatures"
    )

    /**
     * Neutralize signature/integrity self-checks so a re-signed app still runs.
     * A method is treated as a check when its body references a signature API and
     * it either returns a boolean/int (the verdict) or throws (a tamper trap).
     */
    fun bypassSignatureChecks(dexBytes: ByteArray, tmpDir: File): Pair<ByteArray, Report> {
        val dex = DexBackedDexFile(Opcodes.getDefault(), dexBytes)
        val patched = mutableListOf<String>()

        val newClasses = ArrayList<ClassDef>()
        var changed = false

        for (cls in dex.classes) {
            var classChanged = false
            val newMethods = ArrayList<Method>()
            for (m in cls.methods) {
                val forced = maybeForce(m)
                if (forced != null) {
                    newMethods.add(forced)
                    patched.add("${cls.type}->${m.name}")
                    classChanged = true
                } else {
                    newMethods.add(m)
                }
            }
            if (classChanged) {
                changed = true
                newClasses.add(
                    ImmutableClassDef(
                        cls.type, cls.accessFlags, cls.superclass, cls.interfaces,
                        cls.sourceFile, cls.annotations, cls.fields, newMethods
                    )
                )
            } else {
                newClasses.add(cls)
            }
        }

        if (!changed) return dexBytes to Report(emptyList(), 0)

        val tmp = File.createTempFile("theo_dex", ".dex", tmpDir)
        try {
            DexFileFactory.writeDexFile(
                tmp.absolutePath,
                ImmutableDexFile(Opcodes.getDefault(), newClasses)
            )
            return tmp.readBytes() to Report(patched, patched.size)
        } finally {
            tmp.delete()
        }
    }

    /** Returns a replacement method if [m] is a signature check, else null. */
    private fun maybeForce(m: Method): Method? {
        val impl = m.implementation ?: return null
        if (m.accessFlags and (AccessFlags.NATIVE.value or AccessFlags.ABSTRACT.value) != 0) return null

        var refsSig = false
        var hasThrow = false
        for (inst in impl.instructions) {
            if (inst.opcode == Opcode.THROW) hasThrow = true
            val refInst = inst as? ReferenceInstruction ?: continue
            val ref = runCatching { ReferenceUtil.getReferenceString(refInst.reference) }
                .getOrNull() ?: continue
            if (SIG_MARKERS.any { ref.contains(it) }) { refsSig = true }
        }
        if (!refsSig) return null

        val rt = m.returnType
        return when {
            rt == "Z" || rt == "I" || rt == "B" || rt == "S" || rt == "C" ->
                forceReturnConst(m, 1)
            rt == "V" && hasThrow ->
                forceReturnVoid(m)
            else -> null
        }
    }

    private fun paramRegs(m: Method): Int {
        var regs = if (m.accessFlags and AccessFlags.STATIC.value != 0) 0 else 1
        for (p in m.parameterTypes) {
            val s = p.toString()
            regs += if (s == "J" || s == "D") 2 else 1
        }
        return regs
    }

    private fun forceReturnConst(m: Method, value: Int): Method {
        val regCount = paramRegs(m) + 1  // v0 is our free local (params live in the high regs)
        val impl = ImmutableMethodImplementation(
            regCount,
            listOf(
                ImmutableInstruction11n(Opcode.CONST_4, 0, value),
                ImmutableInstruction11x(Opcode.RETURN, 0)
            ),
            null, null
        )
        return ImmutableMethod(
            m.definingClass, m.name, m.parameters, m.returnType,
            m.accessFlags, m.annotations, impl
        )
    }

    private fun forceReturnVoid(m: Method): Method {
        val impl = ImmutableMethodImplementation(
            paramRegs(m),
            listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
            null, null
        )
        return ImmutableMethod(
            m.definingClass, m.name, m.parameters, m.returnType,
            m.accessFlags, m.annotations, impl
        )
    }
}
