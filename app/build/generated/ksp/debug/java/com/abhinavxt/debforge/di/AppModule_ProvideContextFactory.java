package com.abhinavxt.debforge.di;

import android.content.Context;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
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
public final class AppModule_ProvideContextFactory implements Factory<Context> {
  private final Provider<Context> contextProvider;

  private AppModule_ProvideContextFactory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public Context get() {
    return provideContext(contextProvider.get());
  }

  public static AppModule_ProvideContextFactory create(Provider<Context> contextProvider) {
    return new AppModule_ProvideContextFactory(contextProvider);
  }

  public static Context provideContext(Context context) {
    return Preconditions.checkNotNullFromProvides(AppModule.INSTANCE.provideContext(context));
  }
}
