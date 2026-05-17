package com.kesf.backend.service;

import java.util.List;

public interface ObjectStorageService {

    void putObject(String objectKey, byte[] content, String contentType);

    byte[] getObjectBytes(String objectKey);

    void deleteObject(String objectKey);

    void deleteObjectsByPrefix(String prefix);

    List<String> listObjectsByPrefix(String prefix);
}
