package com.github.zxbu.webdavteambition.client;

import com.github.zxbu.webdavteambition.config.AliYunDriveProperties;
import com.github.zxbu.webdavteambition.util.JsonUtil;
import net.sf.webdav.exceptions.WebdavException;
import okhttp3.*;

import org.apache.commons.io.FileUtils;
import org.bouncycastle.util.encoders.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.lang3.StringUtils;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Sign;

import javax.servlet.http.HttpServletRequest;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class AliYunDriverClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(AliYunDriverClient.class);
    private OkHttpClient okHttpClient;
    public AliYunDriveProperties aliYunDriveProperties;

    public AliYunDriverClient(AliYunDriveProperties aliYunDriveProperties) {
        try {
            OkHttpClient okHttpClient = new OkHttpClient.Builder().addInterceptor(new Interceptor() {
                @Override
                public Response intercept(Chain chain) throws IOException {
                    Request request = chain.request();
                    request = request.newBuilder()
                            .removeHeader("User-Agent")
                            .addHeader("User-Agent", aliYunDriveProperties.agent)
                            .removeHeader("authorization")
                            .addHeader("authorization", "Bearer\t" + aliYunDriveProperties.authorization)
                            .addHeader("x-device-id", aliYunDriveProperties.deviceId)
                            .addHeader("x-signature", aliYunDriveProperties.session.signature + "01")
                            .addHeader("x-canary", "client=web,app=adrive,version=v3.17.0")
                            .addHeader("x-request-id", UUID.randomUUID().toString())
                            .build();
                    return chain.proceed(request);
                }
            }).authenticator(new Authenticator() {
                @Override
                public Request authenticate(Route route, Response response) throws IOException {
                    if (response.code() == 401 && response.body() != null && response.body().string().contains("AccessToken")) {
                        String refreshTokenResult;
                        try {
                            if (StringUtils.isEmpty(aliYunDriveProperties.refreshToken)) {
                                throw new NullPointerException();
                            }
                            refreshTokenResult = post("https://api.aliyundrive.com/token/refresh", Collections.singletonMap("refresh_token", aliYunDriveProperties.refreshToken));
                        } catch (Exception e) {
                            refreshTokenResult = post("https://api.aliyundrive.com/token/refresh", Collections.singletonMap("refresh_token", aliYunDriveProperties.refreshTokenNext));
                        }
                        String accessToken = (String) JsonUtil.getJsonNodeValue(refreshTokenResult, "access_token");
                        String refreshToken = (String) JsonUtil.getJsonNodeValue(refreshTokenResult, "refresh_token");
                        String userId = (String) JsonUtil.getJsonNodeValue(refreshTokenResult, "user_id");
                        if (StringUtils.isEmpty(accessToken))
                            throw new IllegalArgumentException("获取accessToken失败");
                        if (StringUtils.isEmpty(refreshToken))
                            throw new IllegalArgumentException("获取refreshToken失败");
                        if (StringUtils.isEmpty(refreshToken))
                            throw new IllegalArgumentException("获取userId失败");
                        aliYunDriveProperties.userId = userId;
                        aliYunDriveProperties.authorization = accessToken;
                        aliYunDriveProperties.refreshToken = refreshToken;
                        aliYunDriveProperties.save();
                        return response.request().newBuilder()
                                .removeHeader("authorization")
                                .header("authorization", accessToken)
                                .build();
                    }
                    return null;
                }
            }).readTimeout(1, TimeUnit.MINUTES)
                    .writeTimeout(1, TimeUnit.MINUTES)
                    .connectTimeout(1, TimeUnit.MINUTES)
                    .build();
            this.okHttpClient = okHttpClient;
            this.aliYunDriveProperties = aliYunDriveProperties;
            init();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void login() {
        // todo 暂不支持登录功能
    }

    public void init() {
        login();
        if (getDriveId() == null) {
            String personalJson = post("/user/get", Collections.emptyMap());
            String driveId = (String) JsonUtil.getJsonNodeValue(personalJson, "default_drive_id");
            aliYunDriveProperties.driveId = driveId;
        }
    }


    public String getDriveId() {
        return aliYunDriveProperties.driveId;
    }


    public Response download(String url, HttpServletRequest httpServletRequest, long size ) {
        Request.Builder builder = new Request.Builder().header("referer", "https://www.aliyundrive.com/");
        String range = httpServletRequest.getHeader("range");
        if (range != null) {
            // 如果range最后 >= size， 则去掉
            String[] split = range.split("-");
            if (split.length == 2) {
                String end = split[1];
                if (Long.parseLong(end) >= size) {
                    range = range.substring(0, range.lastIndexOf('-') + 1);
                }
            }
            builder.header("range", range);
        }

        String ifRange = httpServletRequest.getHeader("if-range");
        if (ifRange != null) {
            builder.header("if-range", ifRange);
        }


        Request request = builder.url(url).build();
        Response response = null;
        try {
            response = okHttpClient.newCall(request).execute();
            return response;
        } catch (IOException e) {
            throw new WebdavException(e);
        }
    }

    public void upload(String url, byte[] bytes, final int offset, final int byteCount) {
        Request request = new Request.Builder()
                .put(RequestBody.create(MediaType.parse(""), bytes, offset, byteCount))
                .url(url).build();
        try (Response response = okHttpClient.newCall(request).execute()){
            LOGGER.info("upload {}, code {}", url, response.code());
            if (!response.isSuccessful()) {
                LOGGER.error("请求失败，url={}, code={}, body={}", url, response.code(), response.body().string());
                throw new WebdavException("请求失败：" + url);
            }
        } catch (IOException e) {
            throw new WebdavException(e);
        }
    }

    public String post(String url, Object body) {
        String bodyAsJson = JsonUtil.toJson(body);
        Request request = new Request.Builder()
                .post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), bodyAsJson))
                .url(getTotalUrl(url)).build();
        try (Response response = okHttpClient.newCall(request).execute()){
            if (!response.isSuccessful()) {
                String res = "";
                try {
                    res = toString(response.body());
                } catch (Exception e) {
                }
                LOGGER.error("请求失败，url={}, code={}, body={}", url, response.code(), res);
                throw new WebdavException("请求失败：" + url);
            }
            String res = toString(response.body());
            LOGGER.info("post {}, body {}, code {} res {}", url, bodyAsJson, response.code(), res);
            return res;
        } catch (IOException e) {
            throw new WebdavException(e);
        }
    }

    public String put(String url, Object body) {
        Request request = new Request.Builder()
                .put(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), JsonUtil.toJson(body)))
                .url(getTotalUrl(url)).build();
        try (Response response = okHttpClient.newCall(request).execute()){
            LOGGER.info("put {}, code {}", url, response.code());
            if (!response.isSuccessful()) {
                LOGGER.error("请求失败，url={}, code={}, body={}", url, response.code(), response.body().string());
                throw new WebdavException("请求失败：" + url);
            }
            return toString(response.body());
        } catch (IOException e) {
            throw new WebdavException(e);
        }
    }

    public String get(String url, Map<String, String> params)  {
        try {
            HttpUrl.Builder urlBuilder = HttpUrl.parse(getTotalUrl(url)).newBuilder();
            Iterator<Map.Entry<String, String>> it = params.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, String> entry = it.next();
                String name = entry.getKey();
                String value = entry.getValue();
                urlBuilder.addQueryParameter(name, value);
            }

            Request request = new Request.Builder().get().url(urlBuilder.build()).build();
            try (Response response = okHttpClient.newCall(request).execute()){
                LOGGER.info("get {}, code {}", urlBuilder.build(), response.code());
                if (!response.isSuccessful()) {
                    throw new WebdavException("请求失败：" + urlBuilder.build().toString());
                }
                return toString(response.body());
            }

        } catch (Exception e) {
            throw new WebdavException(e);
        }

    }

    private String toString(ResponseBody responseBody) throws IOException {
        if (responseBody == null) {
            return null;
        }
        return responseBody.string();
    }

    private String getTotalUrl(String url) {
        if (url.startsWith("http")) {
            return url;
        }
        return aliYunDriveProperties.url + url;
    }
}