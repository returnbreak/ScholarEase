package com.kesf.backend.service;

public interface ObjectStorageService {

    void putObject(String objectKey, byte[] content, String contentType);
}
