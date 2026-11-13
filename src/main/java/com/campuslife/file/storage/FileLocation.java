package com.campuslife.file.storage;

import com.campuslife.file.entity.FileAssetEntity;
import java.io.IOException;

/** Immutable, database-owned location; never assembled from a download request. */
public record FileLocation(String kind, String namespace, String key) {
    public FileLocation {
        if (!("LOCAL".equals(kind)||"S3".equals(kind)) || namespace==null
                || !namespace.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                || key==null || !key.matches("[a-f0-9]{32}[.]pdf")) {
            throw new IllegalArgumentException("Invalid exact file location");
        }
    }
    public static FileLocation of(FileAssetEntity row) {
        return new FileLocation(row.getStorageKind(),row.getStorageId(),row.getStorageKey());
    }
    public static void requireSame(FileAssetEntity before,FileAssetEntity after) throws IOException {
        if (!of(before).equals(of(after)) || !before.getByteSize().equals(after.getByteSize())
                || !before.getSha256().equals(after.getSha256())) { throw new IOException("File location changed"); }
    }
}
