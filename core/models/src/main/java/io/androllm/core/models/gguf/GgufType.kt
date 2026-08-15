package io.androllm.core.models.gguf

/**
 * GGML tensor types as stored in the GGUF tensor index. Ids mirror the
 * GGML_TYPE enum in the vendored llama.cpp tree (ggml.h). The catalog uses the
 * dominant type across `blk.*` tensors to report the model's quantization.
 */
enum class GgufType(val id: Int, val label: String) {
    F32(0, "F32"),
    F16(1, "F16"),
    Q4_0(2, "Q4_0"),
    Q4_1(3, "Q4_1"),
    Q5_0(6, "Q5_0"),
    Q5_1(7, "Q5_1"),
    Q8_0(8, "Q8_0"),
    Q8_1(9, "Q8_1"),
    Q2_K(10, "Q2_K"),
    Q3_K(11, "Q3_K"),
    Q4_K(12, "Q4_K"),
    Q5_K(13, "Q5_K"),
    Q6_K(14, "Q6_K"),
    IQ2_XXS(15, "IQ2_XXS"),
    IQ2_XS(16, "IQ2_XS"),
    IQ3_XXS(17, "IQ3_XXS"),
    IQ1_S(18, "IQ1_S"),
    IQ4_NL(19, "IQ4_NL"),
    IQ3_S(20, "IQ3_S"),
    IQ2_S(21, "IQ2_S"),
    IQ4_XS(22, "IQ4_XS"),
    IQ1_M(23, "IQ1_M"),
    BF16(24, "BF16"),
    Q4_0_4_4(25, "Q4_0_4_4"),
    Q4_0_4_8(26, "Q4_0_4_8"),
    Q4_0_8_8(27, "Q4_0_8_8"),
    TQ1_0(28, "TQ1_0"),
    TQ2_0(29, "TQ2_0"),
    IQ4_NL_4_4(30, "IQ4_NL_4_4"),
    IQ4_NL_4_8(31, "IQ4_NL_4_8"),
    IQ4_NL_8_8(32, "IQ4_NL_8_8"),
    MXFP4_MOE(33, "MXFP4_MOE"),
    NVFP4(34, "NVFP4"),
    Q1_0(35, "Q1_0"),
    Q2_0(36, "Q2_0"),
    I8(37, "I8"),
    I16(38, "I16"),
    I32(39, "I32"),
    I64(40, "I64"),
    F64(41, "F64"),
    IQ1_MX(42, "IQ1_MX"),
    F8_E4M3(43, "F8_E4M3"),
    F8_E5M2(44, "F8_E5M2"),
    W8_A16(45, "W8_A16"),
    I4(46, "I4"),
    IQ4_0(47, "IQ4_0"),
    IQ3_M(48, "IQ3_M");

    companion object {
        fun byId(id: Int): GgufType? = entries.firstOrNull { it.id == id }
    }
}
