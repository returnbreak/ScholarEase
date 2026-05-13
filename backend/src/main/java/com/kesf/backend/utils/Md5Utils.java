package com.kesf.backend.utils;

import org.springframework.util.DigestUtils;

import java.io.IOException;
import java.io.InputStream;

public final class Md5Utils {

    private Md5Utils() {
    }

    public static String md5Hex(InputStream inputStream) throws IOException {
        return DigestUtils.md5DigestAsHex(inputStream);
    }
}
