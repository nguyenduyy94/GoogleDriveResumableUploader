package com.nguyenduyy;

import org.apache.commons.io.IOUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.logging.Logger;

import static java.net.HttpURLConnection.*;
import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static java.nio.charset.StandardCharsets.UTF_8;

public class GoogleDriveResumableUploader {
    private Logger logger = Logger.getLogger(GoogleDriveResumableUploader.class.getSimpleName());
    String USER_AGENT = "User-Agent";
    String MOZILLA_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_11_2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/48.0.2564.103 Safari/537.36";

    private String UPLOAD_ENDPOINT = "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable";
    private String REFRESH_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private String resumeSession = null;
    private Long uploadedSize = 0L;
    private Credential credential;
    private Integer CHUNK_SIZE = 256 * 8 * 1024; //2MB
    private Long fileSize = 0L;
    private String uploadedFileId = null;
    private onTokenRefreshed onTokenRefreshed = null;

    public GoogleDriveResumableUploader(FileMetadata metadata, Long sizeInByte, Credential credential) throws IOException, JSONException {
        JSONObject data = new JSONObject();
        data.put("name", metadata.getName());
        data.put("parents",  metadata.getParents() != null ? metadata.getParents() : Collections.emptyList());
        String payload = data.toString();
        this.credential = credential;
        HttpURLConnection connection = createConnection(UPLOAD_ENDPOINT, "POST",payload, getHeaders());
        if (isAccessTokenExpired(connection.getResponseCode())) {
            refreshToken();
            connection = createConnection(UPLOAD_ENDPOINT, "POST", payload, getHeaders());
        }
        resumeSession = connection.getHeaderField("Location");
        logger.info("Session: " + resumeSession);
        fileSize = sizeInByte;
    }

    public void upload(byte[] bytes, int index , int length) throws IOException, JSONException {
        assert resumeSession != null;
        Map<String, String> headers = new HashMap<>();
        byte[] uploadByte = Arrays.copyOfRange(bytes, index, length);
        headers.put("Content-Length", String.valueOf(uploadByte.length));
        String contentRange = getContentRange(uploadByte);
        headers.put("Content-Range",contentRange);
        headers.put("Content-Type", "application/octet-stream");
        HttpURLConnection connection = createConnection(resumeSession, "PUT", uploadByte, headers);
        final int responseCode = connection.getResponseCode();
        logger.info("Upload return code " + responseCode);
        if (isValidCode(responseCode) || isResumeCode(responseCode)) {
            uploadedSize += uploadByte.length; //TODO: don't assume server get all bytes
            if (isValidCode(responseCode)) {
                uploadedFileId = getFileId(connection);
                logger.info("Finish uploading. File id: " + uploadedFileId);
            }
            logger.warning("Upload chunk " + contentRange + " success");
        } else {
            //TODO : resume when things go wrong
            logger.warning("Upload chunk " + contentRange + " fail :\n " + logConnectResponse(connection));

        }
    }

    public void upload(InputStream inputStream) throws IOException, JSONException {
        byte[] buffer = new byte[CHUNK_SIZE];
        int count = 0;
        while((count = inputStream.read(buffer, 0, CHUNK_SIZE)) > 0) {
            logger.info(String.format("Upload %d bytes", count));
            upload(buffer, 0, count);
            buffer = new byte[CHUNK_SIZE];
        }
    }

    private String getContentRange(byte[] bytes) {
        Long from = uploadedSize;
        Long to = uploadedSize + bytes.length - 1;
        return "bytes " + from + "-" + to + "/" + fileSize;
    }
    private Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + credential.accessToken);
        headers.put("Content-Type", "application/json");
        return headers;
    }

    private HttpURLConnection createConnection(String path, String method, String payload, Map<String, String> headers) throws IOException {
        byte[] bytes = payload.getBytes(UTF_8);
        return createConnection(path, method, bytes, headers);
    }

    private HttpURLConnection createConnection(String path, String method, byte[] bytes, Map<String, String> headers) throws IOException  {
        URL url = new URL(path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setDoOutput(true);
        connection.setRequestMethod(method);
        connection.setConnectTimeout(50000); // less than 60s so that this will return before the parent http request is timeout
        if (headers != null) {
            for (String key : headers.keySet()) {
                connection.setRequestProperty(key, headers.get(key));
            }
        }
        connection.setRequestProperty(USER_AGENT, MOZILLA_UA);
        connection.setRequestProperty("Content-Length", Integer.toString(bytes.length));
        OutputStream outputStream = connection.getOutputStream();
        outputStream.write(bytes);
        outputStream.close();
        return connection;
    }

    private String getFileId(HttpURLConnection connection) throws IOException, JSONException {
        String raw = IOUtils.toString(connection.getInputStream(), UTF_8);
        JSONObject jsonObject = new JSONObject(raw);
        return jsonObject.getString("id");
    }

    private  boolean isValidCode(int responseCode) {
        return responseCode == HTTP_OK || responseCode == HTTP_ACCEPTED || responseCode == HTTP_CREATED || responseCode == HTTP_NO_CONTENT;
    }

    private boolean isResumeCode(int responseCode) {
        return responseCode == 308;
    }

    private boolean isAccessTokenExpired(int responseCode) {
        return responseCode == 401;
    }

    public String getUploadedFileId() {
        return uploadedFileId;
    }

    public void refreshToken() throws IOException, JSONException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        HttpURLConnection connection = createConnection(REFRESH_TOKEN_ENDPOINT, "POST", credential.getRefreshTokenPayload().getBytes(UTF_8), headers);
        if (isValidCode(connection.getResponseCode())) {
            String payload = IOUtils.toString(connection.getInputStream(), UTF_8);
            JSONObject jsonObject = new JSONObject(payload);
            this.credential.accessToken = jsonObject.getString("access_token");
            logger.info("Refresh token OK");
            if (onTokenRefreshed != null) {
                onTokenRefreshed.execute(this.credential.accessToken);
            }
        } else {
            logger.warning("Can not refresh token " + logConnectResponse(connection));
        }

    }

    public void setOnTokenRefreshed(onTokenRefreshed action) {
        this.onTokenRefreshed = action;
    }

    private String logConnectResponse(HttpURLConnection connection) throws IOException {
        return "code" + connection.getResponseCode() + "\n" + IOUtils.toString(connection.getErrorStream(), UTF_8) + "\n" + connection.getHeaderFields();
    }

    public static class Credential {
        String accessToken, refreshToken, clientId, secret;
        public Credential(String accessToken, String refreshToken, String clientId, String secret) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.clientId = clientId;
            this.secret = secret;
        }

        public String getRefreshTokenPayload() {
            return String.format("client_id=%s&client_secret=%s&refresh_token=%s&grant_type=refresh_token", clientId, secret, refreshToken);
        }

        public void setAccessToken(String token) {
            this.accessToken = token;
        }
    }

    public static class FileMetadata {
        String name;
        List<String> parents;
        public FileMetadata(String name, List<String> parents) {
            this.name = name;
            this.parents = parents;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<String> getParents() {
            return parents;
        }

        public void setParents(List<String> parents) {
            this.parents = parents;
        }
    }

    public interface onTokenRefreshed {
        void execute(String newAccessToken);
    }
}