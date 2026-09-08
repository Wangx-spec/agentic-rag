package com.agenticrag.rag.storage;

import java.io.InputStream;

/**
 * 文件存储客户端接口
 * 用于抽象原始文件的存储操作（本地文件系统、对象存储等）
 */
public interface StorageClient {
    /**
     * 保存文件
     *
     * @param filename 原始文件名
     * @param in       文件输入流
     * @return 存储后的文件路径（可用于后续获取或删除）
     * @throws Exception 保存失败时抛出
     */
    String save(String filename, InputStream in) throws Exception;

    /**
     * 获取文件输入流
     *
     * @param filePath 文件路径（由 save 方法返回）
     * @return 文件输入流
     * @throws Exception 获取失败时抛出
     */
    InputStream get(String filePath) throws Exception;

        /**
     * 删除文件
     *
     * @param filePath 文件路径（由 save 方法返回）
     * @throws Exception 删除失败时抛出
     */
    void delete(String filePath) throws Exception;
}
