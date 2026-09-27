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
public final class ProviderEvents_Factory implements Factory<ProviderEvents> {
  @Override
  public ProviderEvents get() {
    return newInstance();
  }

  public static ProviderEvents_Factory create() {
    return InstanceHolder.INSTANCE;
  }

  public static ProviderEvents newInstance() {
    return new ProviderEvents();
  }

  private static final class InstanceHolder {
    static final ProviderEvents_Factory INSTANCE = new ProviderEvents_Factory();
  }
}
