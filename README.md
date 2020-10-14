# GoogleDriveResumableUploader

Upload a file to Google Drive chunk by chunk, useful for large files

Example :
```$xslt
    FileMetadata fileMetadata = new FileMetadata("filename", Arrays.asList("folder", "subfolder"));
    Credential credential = ....
    GoogleDriveResumableUploader uploader = new GoogleDriveResumableUploader(fileMetadata, 1232211L, credential);
    uploader.onTokenRefreshed(newAccessToken -> ... <store it somewhere>)
    uploader.upload(...)
```