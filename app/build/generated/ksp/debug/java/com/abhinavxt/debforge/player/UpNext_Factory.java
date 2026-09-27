package com.abhinavxt.debforge.player;

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
public final class UpNext_Factory implements Factory<UpNext> {
  @Override
  public UpNext get() {
    return newInstance();
  }

  public static UpNext_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static UpNext newInstance() {
    return new UpNext();
  }

  private static final class InstanceHolder {
    static final UpNext_Factory INSTANCE = new UpNext_Factory();
  }
}
