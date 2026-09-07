package com.agenticrag.rag.ingest;

import java.io.InputStream;

/**
 * 文档解析器：把上传文档抽取为纯文本
 * <p>
 * 计划方法：
 * - boolean supports(String filename)   按扩展名判断是否支持（pdf/txt/md）
 * - String parse(InputStream in)        抽取纯文本（PDF 走 PDFBox）
 */
public interface DocumentParser {

    boolean supports(String filename);

    String parse(InputStream in) throws Exception;
}
