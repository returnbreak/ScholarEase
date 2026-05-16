package com.kesf.backend.service.impl;

import com.kesf.backend.config.MinioProperties;
import com.kesf.backend.exception.BusinessException;
import com.kesf.backend.exception.ErrorCode;
import com.kesf.backend.service.ObjectStorageService;
import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.messages.DeleteObject;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

@Service
@RequiredArgsConstructor
public class MinioObjectStorageServiceImpl implements ObjectStorageService {

    private final MinioProperties properties;

    private volatile MinioClient minioClient;

    @Override
    public void putObject(String objectKey, byte[] content, String contentType) {
        try {
            ensureBucketExists();
            byte[] objectContent = content == null ? new byte[0] : content;
            client().putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucketName())
                    .object(objectKey)
                    .contentType(contentType)
                    .stream(new ByteArrayInputStream(objectContent), objectContent.length, -1)
                    .build());
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.UPLOAD_FAILED,
                    "MinIO upload failed: " + exception.getMessage());
        }
    }

    @Override
    public void deleteObject(String objectKey) {
        deleteAllVersions(objectKey);
    }

    @Override
    public void deleteObjectsByPrefix(String prefix) {
        List<ObjectVersion> versions = listVersionsByPrefix(prefix);
        if (versions.isEmpty()) {
            return;
        }
        batchDeleteVersions(versions);
    }

    @Override
    public List<String> listObjectsByPrefix(String prefix) {
        try {
            Iterable<Result<Item>> results = client().listObjects(ListObjectsArgs.builder()
                    .bucket(properties.getBucketName())
                    .prefix(prefix)
                    .recursive(true)
                    .includeVersions(true)
                    .build());
            return StreamSupport.stream(results.spliterator(), false)
                    .map(result -> {
                        try {
                            return result.get().objectName();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .toList();
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.DELETE_FAILED,
                    "MinIO list objects failed: " + exception.getMessage());
        }
    }

    private void deleteAllVersions(String objectKey) {
        List<ObjectVersion> versions = listVersionsOfObject(objectKey);
        if (versions.isEmpty()) {
            return;
        }
        batchDeleteVersions(versions);
    }

    private List<ObjectVersion> listVersionsOfObject(String objectKey) {
        try {
            Iterable<Result<Item>> results = client().listObjects(ListObjectsArgs.builder()
                    .bucket(properties.getBucketName())
                    .prefix(objectKey)
                    .recursive(false)
                    .includeVersions(true)
                    .build());
            List<ObjectVersion> versions = new ArrayList<>();
            for (Result<Item> result : results) {
                try {
                    Item item = result.get();
                    if (item.objectName().equals(objectKey)) {
                        versions.add(new ObjectVersion(item.objectName(),
                                item.versionId() != null ? item.versionId() : "null"));
                    }
                } catch (Exception ignored) {
                }
            }
            return versions;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.DELETE_FAILED,
                    "MinIO list versions failed: " + exception.getMessage());
        }
    }

    private List<ObjectVersion> listVersionsByPrefix(String prefix) {
        try {
            Iterable<Result<Item>> results = client().listObjects(ListObjectsArgs.builder()
                    .bucket(properties.getBucketName())
                    .prefix(prefix)
                    .recursive(true)
                    .includeVersions(true)
                    .build());
            List<ObjectVersion> versions = new ArrayList<>();
            for (Result<Item> result : results) {
                try {
                    Item item = result.get();
                    versions.add(new ObjectVersion(item.objectName(),
                            item.versionId() != null ? item.versionId() : "null"));
                } catch (Exception ignored) {
                }
            }
            return versions;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.DELETE_FAILED,
                    "MinIO list versions failed: " + exception.getMessage());
        }
    }

    private void batchDeleteVersions(List<ObjectVersion> versions) {
        List<DeleteObject> objects = versions.stream()
                .map(v -> new DeleteObject(v.name, v.versionId))
                .toList();
        try {
            Iterable<Result<io.minio.messages.DeleteError>> results =
                    client().removeObjects(RemoveObjectsArgs.builder()
                            .bucket(properties.getBucketName())
                            .objects(objects)
                            .build());
            for (Result<io.minio.messages.DeleteError> result : results) {
                try {
                    io.minio.messages.DeleteError error = result.get();
                    throw new BusinessException(ErrorCode.DELETE_FAILED,
                            "MinIO batch delete failed for " + error.objectName() + ": " + error.message());
                } catch (BusinessException e) {
                    throw e;
                } catch (Exception ignored) {
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.DELETE_FAILED,
                    "MinIO batch delete failed: " + exception.getMessage());
        }
    }

    private void ensureBucketExists() throws Exception {
        boolean exists = client().bucketExists(BucketExistsArgs.builder()
                .bucket(properties.getBucketName())
                .build());
        if (!exists) {
            client().makeBucket(MakeBucketArgs.builder()
                    .bucket(properties.getBucketName())
                    .build());
        }
    }

    private record ObjectVersion(String name, String versionId) {
    }

    private MinioClient client() {
        MinioClient current = minioClient;
        if (current == null) {
            synchronized (this) {
                current = minioClient;
                if (current == null) {
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
