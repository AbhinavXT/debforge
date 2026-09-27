package com.abhinavxt.debforge.player;

import android.content.Context;
import com.abhinavxt.debforge.data.repository.DownloadsRepository;
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
public final class SubtitleResolver_Factory implements Factory<SubtitleResolver> {
  private final Provider<Context> contextProvider;

  private final Provider<DownloadsRepository> repositoryProvider;

  private SubtitleResolver_Factory(Provider<Context> contextProvider,
      Provider<DownloadsRepository> repositoryProvider) {
    this.contextProvider = contextProvider;
    this.repositoryProvider = repositoryProvider;
  }

  @Override
  public SubtitleResolver get() {
    return newInstance(contextProvider.get(), repositoryProvider.get());
  }

  public static SubtitleResolver_Factory create(Provider<Context> contextProvider,
      Provider<DownloadsRepository> repositoryProvider) {
    return new SubtitleResolver_Factory(contextProvider, repositoryProvider);
  }

  public static SubtitleResolver newInstance(Context context, DownloadsRepository repository) {
    return new SubtitleResolver(context, repository);
  }
}
