package com.kesf.backend.service.impl;

import com.kesf.backend.config.MinioProperties;
import com.kesf.backend.exception.BusinessException;
import com.kesf.backend.exception.ErrorCode;
import com.kesf.backend.service.ObjectStorageService;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;

/**
 * MinIO 对象存储服务实现类
 * 负责与 MinIO 服务器进行交互，处理文件的上传和存储桶的管理
 */
@Service
@RequiredArgsConstructor
public class MinioObjectStorageServiceImpl implements ObjectStorageService {

    // MinIO 相关的配置属性（如 endpoint、访问密钥、存储桶名称等）
    private final MinioProperties properties;

    // MinIO 客户端实例
    // 使用 volatile 修饰以保证在多线程环境下的内存可见性，配合双重检查锁定实现安全的延迟初始化
    private volatile MinioClient minioClient;

    /**
     * 将对象（文件）上传到 MinIO 存储桶中
     *
     * @param objectKey   对象键（文件在存储桶中的完整路径和名称）
     * @param content     文件内容的字节数组
     * @param contentType 文件的 MIME 类型（如 "application/pdf"）
     */
    @Override
    public void putObject(String objectKey, byte[] content, String contentType) {
        try {
            // 在上传前确保目标存储桶已经存在
            ensureBucketExists();
            // 防止传入空内容导致异常，若为空则初始化为一个空字节数组
            byte[] objectContent = content == null ? new byte[0] : content;
            
            // 构建并执行上传对象的请求
            client().putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucketName()) // 指定存储桶
                    .object(objectKey) // 指定对象名称
                    .contentType(contentType) // 设置文件类型
                    // 将字节数组转为输入流进行上传，明确指定对象大小，分片大小设为 -1 表示由客户端自动推断
                    .stream(new ByteArrayInputStream(objectContent), objectContent.length, -1) 
                    .build());
        } catch (Exception exception) {
            // 捕获所有 MinIO 相关异常并转化为自定义的业务异常，方便全局统一处理
            throw new BusinessException(ErrorCode.UPLOAD_FAILED,
                    "MinIO upload failed: " + exception.getMessage());
        }
    }

    /**
     * 检查配置中指定的存储桶是否存在，如果不存在则自动创建该存储桶
     *
     * @throws Exception 如果与 MinIO 交互失败则抛出异常
     */
    private void ensureBucketExists() throws Exception {
        // 检查存储桶是否存在
        boolean exists = client().bucketExists(BucketExistsArgs.builder()
                .bucket(properties.getBucketName())
                .build());
        // 若不存在，则发起创建存储桶的请求
        if (!exists) {
            client().makeBucket(MakeBucketArgs.builder()
                    .bucket(properties.getBucketName())
                    .build());
        }
    }

    /**
     * 获取 MinIO 客户端实例
     * 使用双重检查锁定 (Double-Checked Locking) 机制来实现线程安全的懒加载
     * 
     * @return 初始化的 MinioClient 实例
     */
    private MinioClient client() {
        MinioClient current = minioClient;
        // 第一次检查，如果不为 null 则直接返回，避免不必要的同步开销
        if (current == null) {
            synchronized (this) {
                // 加锁后第二次检查，防止多个线程同时通过了第一次检查而重复实例化
                current = minioClient;
                if (current == null) {
                    // 实例化并配置 MinioClient
                    current = MinioClient.builder()
                            .endpoint(properties.getEndpoint())
                            .credentials(properties.getAccessKey(), properties.getSecretKey())
                            .build();
                    minioClient = current;
                }
            }
        }
        return current;
    }
}
