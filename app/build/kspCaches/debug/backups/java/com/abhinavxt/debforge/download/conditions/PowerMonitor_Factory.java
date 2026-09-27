package com.abhinavxt.debforge.download.conditions;

import android.content.Context;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")
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
public final class PowerMonitor_Factory implements Factory<PowerMonitor> {
  private final Provider<Context> contextProvider;

  private PowerMonitor_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public PowerMonitor get() {
    return newInstance(contextProvider.get());
  }

  public static PowerMonitor_Factory create(Provider<Context> contextProvider) {
    return new PowerMonitor_Factory(contextProvider);
  }

  public static PowerMonitor newInstance(Context context) {
    return new PowerMonitor(context);
  }
}
