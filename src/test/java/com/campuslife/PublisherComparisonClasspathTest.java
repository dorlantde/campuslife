package com.campuslife;

import java.io.File;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PublisherComparisonClasspathTest {
 @Test @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="COUPON_BENCH_COMPARE",matches="OLD_NEW")
 void verifiesOldSnapshotAndIdenticalProductionDependencies()throws Exception{
  var identity=PublisherComparisonClasspath.validate();assertThat(identity.get("oldFiles")).isEqualTo(376);assertThat(identity.get("productionDependencies")).isEqualTo(115);
 }
 @Test void replacesRatherThanOverlaysProductionDirectory()throws Exception{
  String cp=String.join(File.pathSeparator,"target/test-classes","target/classes","a-library.jar");
  var old=PublisherComparisonClasspath.select(cp,"OLD");assertThat(old.split(java.util.regex.Pattern.quote(File.pathSeparator))).hasSize(3);
  assertThat(old).contains(PublisherComparisonClasspath.BASE.resolve("classes").toString()).doesNotContain(PublisherComparisonClasspath.CURRENT.toString());
  assertThat(PublisherComparisonClasspath.select(cp,"NEW")).contains(PublisherComparisonClasspath.CURRENT.toString()).doesNotContain(PublisherComparisonClasspath.BASE.toString());
 }
 @Test void rejectsMissingDuplicateOrOverlaidProductionDirectories(){
  assertThatThrownBy(()->PublisherComparisonClasspath.select("target/test-classes","OLD")).isInstanceOf(IllegalStateException.class);
  assertThatThrownBy(()->PublisherComparisonClasspath.select(String.join(File.pathSeparator,"target/classes","target/classes"),"OLD")).isInstanceOf(IllegalStateException.class);
  assertThatThrownBy(()->PublisherComparisonClasspath.select(String.join(File.pathSeparator,"target/classes",PublisherComparisonClasspath.BASE.resolve("classes").toString()),"OLD")).isInstanceOf(IllegalStateException.class);
 }
 @Test void rejectsFakeBinaryAndExecutableJarOverlay(){
  assertThatThrownBy(()->PublisherComparisonClasspath.select("target/classes","BATCH_ONE")).isInstanceOf(IllegalStateException.class);
  assertThatThrownBy(()->PublisherComparisonClasspath.select(String.join(File.pathSeparator,"target/classes","target/campuslife-0.0.1-SNAPSHOT.jar"),"OLD")).isInstanceOf(IllegalStateException.class);
 }
}
