package ru.neurosteno.core.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StreamingModelFilesTest {
    @Test
    fun `vosk zipformer layout resolves to int8 encoder and joiner`() {
        val f = StreamingModelFiles.resolve(listOf("bpe.model", "decoder.onnx", "encoder.int8.onnx", "joiner.int8.onnx", "README.md", "tokens.txt"))
        assertEquals(StreamingModelFiles("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt"), f)
    }

    @Test
    fun `int8 is preferred when both variants exist`() {
        val f = StreamingModelFiles.resolve(listOf("encoder.onnx", "encoder.int8.onnx", "decoder.onnx", "decoder.int8.onnx", "joiner.onnx", "joiner.int8.onnx", "tokens.txt"))
        assertEquals("encoder.int8.onnx", f.encoder)
        assertEquals("decoder.int8.onnx", f.decoder)
        assertEquals("joiner.int8.onnx", f.joiner)
    }

    @Test
    fun `fp32 only is accepted`() {
        val f = StreamingModelFiles.resolve(listOf("encoder-epoch-99.onnx", "decoder-epoch-99.onnx", "joiner-epoch-99.onnx", "tokens.txt"))
        assertEquals("encoder-epoch-99.onnx", f.encoder)
    }

    @Test
    fun `missing files are listed`() {
        val e = assertThrows(IllegalArgumentException::class.java) { StreamingModelFiles.resolve(listOf("encoder.int8.onnx", "tokens.txt")) }
        assertTrue(e.message!!.contains("decoder") && e.message!!.contains("joiner"), e.message)
    }
}
