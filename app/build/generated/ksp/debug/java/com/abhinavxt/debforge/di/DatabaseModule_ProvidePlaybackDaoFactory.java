package com.abhinavxt.debforge.di;

import com.abhinavxt.debforge.data.local.DebForgeDatabase;
import com.abhinavxt.debforge.data.playback.PlaybackDao;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata
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
public final class DatabaseModule_ProvidePlaybackDaoFactory implements Factory<PlaybackDao> {
  private final Provider<DebForgeDatabase> dbProvider;

  private DatabaseModule_ProvidePlaybackDaoFactory(Provider<DebForgeDatabase> dbProvider) {
    this.dbProvider = dbProvider;
  }

  @Override
  public PlaybackDao get() {
    return providePlaybackDao(dbProvider.get());
  }

  public static DatabaseModule_ProvidePlaybackDaoFactory create(
      Provider<DebForgeDatabase> dbProvider) {
    return new DatabaseModule_ProvidePlaybackDaoFactory(dbProvider);
  }

  public static PlaybackDao providePlaybackDao(DebForgeDatabase db) {
    return Preconditions.checkNotNullFromProvides(DatabaseModule.INSTANCE.providePlaybackDao(db));
  }
}
