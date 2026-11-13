package com.campuslife.file;

import com.campuslife.file.config.S3StorageProperties;
import com.campuslife.file.storage.*;
import java.io.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;

class S3StoragePolicyTest {
    @TempDir Path root;
    private static final String NS="12345678-1234-1234-1234-123456789abc";
    @ParameterizedTest @ValueSource(strings={"","/","../other.pdf","objects/","UPPER.pdf","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.pdf/"})
    void rejectsNonExactKeysBeforeAnyNetwork(String key) {
        assertThatThrownBy(()->new FileLocation("S3",NS,key)).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings={"file:///etc/data","http://example.com","http://127.0.0.1:8333/path","http://user:password@127.0.0.1:8333","http://127.0.0.1:8333?x=1","http://127.0.0.1:8333/#x","not-a-url"})
    void invalidEndpointCannotTurnConfigurationIntoArbitraryTarget(String endpoint) {
        assertThatThrownBy(()->new S3StorageProperties(true,endpoint,"us-east-1","fixture-bucket",NS,"fixture","secret"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid explicit S3 storage configuration");
    }
    @Test void secretsAreRedactedAndDisabledSettingsNeedNoCredentials() {
        assertThat(new S3StorageProperties(false,null,null,null,null,null,null).enabled()).isFalse();
        var settings=new S3StorageProperties(true,"http://127.0.0.1:8333","us-east-1","fixture-bucket",NS,"private-key","private-secret");
        assertThat(settings.toString()).doesNotContain("private-key","private-secret");
    }
    @Test void disabledS3RejectsPersistedS3WithoutFallbackAndLocalStillWorks() throws Exception {
        var local=new LocalFileStorage(root.toString(),Clock.systemUTC());
        var providers=new DefaultListableBeanFactory();var storage=new FileStorage(local,providers.getBeanProvider(S3FileStorage.class),"LOCAL");
        var key="a".repeat(32)+".pdf";var row=new com.campuslife.file.entity.FileAssetEntity();row.setStorageKind("S3");row.setStorageId(NS);row.setStorageKey(key);row.setByteSize(3L);
        assertThatThrownBy(()->storage.open(row)).isInstanceOf(IOException.class);
        assertThatThrownBy(()->new FileStorage(local,providers.getBeanProvider(S3FileStorage.class),"S3")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new FileStorage(local,providers.getBeanProvider(S3FileStorage.class),"OTHER")).isInstanceOf(IllegalArgumentException.class);
        try(var input=new ByteArrayInputStream(new byte[]{1,2,3})) { var received=local.receive(input);local.move(received,key);local.releaseTemporary(received.path()); }
        row.setStorageKind("LOCAL");row.setStorageId(local.storageId());
        try(var input=storage.open(row)) { assertThat(input.readAllBytes()).containsExactly((byte)1,(byte)2,(byte)3); }
        assertThat(storage.lock(key)).isSameAs(local.lock(key));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThatThrownBy(()->storage.open(row)).isInstanceOf(IOException.class).hasMessageContaining("transaction"); }
        finally { TransactionSynchronizationManager.clear(); }
    }
    @Test void remoteClientConstructionDoesNotContactEndpointAndRejectsSqlIo() throws Exception {
        try(var remote=new S3FileStorage(new S3StorageProperties(true,"http://127.0.0.1:1","us-east-1","fixture-bucket",NS,"fixture","secret"))) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try { assertThatThrownBy(()->remote.delete(new FileLocation("S3",NS,"a".repeat(32)+".pdf")))
                    .isInstanceOf(IOException.class).hasMessageContaining("transaction"); }
            finally { TransactionSynchronizationManager.clear(); }
        }
    }
}
