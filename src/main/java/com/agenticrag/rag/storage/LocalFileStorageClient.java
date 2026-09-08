package com.agenticrag.rag.storage;

import com.agenticrag.config.RagProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 本地文件系统存储客户端实现
 * 文件存储在配置的 data-dir/files 目录下
 */
@Component
public class LocalFileStorageClient implements StorageClient {
    /**
     * 存储目录
     */
    private final RagProperties ragProperties;
    private Path storagePath;

    public LocalFileStorageClient(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
    }

    /**
     * 初始化存储目录
     */
    @PostConstruct 
    public void init() throws IOException{
        String dataDir = ragProperties.getDataDir();
        this.storagePath = Paths.get(dataDir, "files").toAbsolutePath().normalize();

        if (!Files.exists(storagePath)){
            Files.createDirectories(storagePath);
        }
    }

    /**
     * 保存文件
     *
     * @param filename 原始文件名
     * @param in       文件输入流
     * @return 存储后的文件路径（可用于后续获取或删除）
     * @throws Exception 保存失败时抛出
     */
    @Override
    public String save(String filename, InputStream in) throws Exception {

        String extension = "";
        int lastDotIndex = filename.lastIndexOf('.');
        if (lastDotIndex > 0){
            extension = filename.substring(lastDotIndex);
        }

        String uniqueFilename = UUID.randomUUID().toString() + extension;
        Path filePath = storagePath.resolve(uniqueFilename);
        Files.copy(in, filePath, StandardCopyOption.REPLACE_EXISTING);
        String finalFileName = "files/" + uniqueFilename;
        return finalFileName;
    }

    /**
     * 获取文件输入流
     *
     * @param filePath 文件路径（如 "files/xxx.pdf"）
     * @return 文件输入流
     * @throws Exception 获取失败时抛出
     */
    @Override 
    public InputStream get(String filePath) throws Exception{
        // filePath 是相对路径，如 "files/xxx.pdf"
        Path fullPath = storagePath.resolve(filePath.replaceFirst("^files/", ""));
        if (!Files.exists(fullPath)) {
            throw new IOException("文件不存在: " + filePath);
        }
        
        return Files.newInputStream(fullPath);
        
    }

    /**
     * 删除文件
     *
     * @param filePath 文件路径（如 "files/xxx.pdf"）
     * @throws Exception 删除失败时抛出
     */
    @Override
    public void delete(String filePath) throws Exception {
        Path fullPath = storagePath.resolve(filePath.replaceFirst("^files/", ""));
        
        if (Files.exists(fullPath)) {
            Files.delete(fullPath);
        }
    }


}
