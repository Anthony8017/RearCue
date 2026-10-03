package com.rearcue.poc.voice

import com.k2fsa.sherpa.onnx.OfflineTtsCallback
import java.lang.Integer
import kotlin.test.Test
import kotlin.test.assertEquals

class KokoroTtsCallbackSignatureTest {

    @Test
    fun `JNI callback exposes boxed integer invoke for float array`() {
        val method = KokoroTtsCallback::class.java.getMethod("invoke", FloatArray::class.java)

        assertEquals(Integer::class.java, method.returnType)
        assertEquals(FloatArray::class.java, method.parameterTypes.single())
    }

    @Test
    fun `upstream callback interface matches native JNI lookup`() {
        val method = OfflineTtsCallback::class.java.getMethod("invoke", FloatArray::class.java)

        assertEquals(Integer::class.java, method.returnType)
        assertEquals(FloatArray::class.java, method.parameterTypes.single())
    }
}
