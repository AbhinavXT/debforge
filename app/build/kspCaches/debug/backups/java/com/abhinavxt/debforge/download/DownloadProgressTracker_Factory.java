package com.abhinavxt.debforge.download;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava",
    "cast",
    "deprecation",
    "nullness:initialization.field.uninitialized"
})
public final class DownloadProgressTracker_Factory implements Factory<DownloadProgressTracker> {
  @Override
  public DownloadProgressTracker get() {
    return newInstance();
  }

  public static DownloadProgressTracker_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static DownloadProgressTracker newInstance() {
    return new DownloadProgressTracker();
  }

  private static final class InstanceHolder {
    static final DownloadProgressTracker_Factory INSTANCE = new DownloadProgressTracker_Factory();
  }
}
