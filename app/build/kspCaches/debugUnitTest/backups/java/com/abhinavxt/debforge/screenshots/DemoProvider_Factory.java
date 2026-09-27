package com.abhinavxt.debforge.screenshots;

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
public final class DemoProvider_Factory implements Factory<DemoProvider> {
  @Override
  public DemoProvider get() {
    return newInstance();
  }

  public static DemoProvider_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static DemoProvider newInstance() {
    return new DemoProvider();
  }

  private static final class InstanceHolder {
    static final DemoProvider_Factory INSTANCE = new DemoProvider_Factory();
  }
}
