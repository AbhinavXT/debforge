package com.abhinavxt.debforge.ui.add;

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
public final class PendingAddStore_Factory implements Factory<PendingAddStore> {
  @Override
  public PendingAddStore get() {
    return newInstance();
  }

  public static PendingAddStore_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static PendingAddStore newInstance() {
    return new PendingAddStore();
  }

  private static final class InstanceHolder {
    static final PendingAddStore_Factory INSTANCE = new PendingAddStore_Factory();
  }
}
