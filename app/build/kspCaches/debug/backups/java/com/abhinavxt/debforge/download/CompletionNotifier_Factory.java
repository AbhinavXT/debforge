package com.abhinavxt.debforge.download;

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
public final class CompletionNotifier_Factory implements Factory<CompletionNotifier> {
  private final Provider<Context> contextProvider;

  private CompletionNotifier_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public CompletionNotifier get() {
    return newInstance(contextProvider.get());
  }

  public static CompletionNotifier_Factory create(Provider<Context> contextProvider) {
    return new CompletionNotifier_Factory(contextProvider);
  }

  public static CompletionNotifier newInstance(Context context) {
    return new CompletionNotifier(context);
  }
}
