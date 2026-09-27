package com.abhinavxt.debforge.data.playback;

import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
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
public final class PlaybackPositions_Factory implements Factory<PlaybackPositions> {
  private final Provider<PlaybackDao> daoProvider;

  private PlaybackPositions_Factory(Provider<PlaybackDao> daoProvider) {
    this.daoProvider = daoProvider;
  }

  @Override
  public PlaybackPositions get() {
    return newInstance(daoProvider.get());
  }

  public static PlaybackPositions_Factory create(Provider<PlaybackDao> daoProvider) {
    return new PlaybackPositions_Factory(daoProvider);
  }

  public static PlaybackPositions newInstance(PlaybackDao dao) {
    return new PlaybackPositions(dao);
  }
}
