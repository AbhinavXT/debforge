package com.abhinavxt.debforge.di;

import com.abhinavxt.debforge.data.local.DebForgeDatabase;
import com.abhinavxt.debforge.data.metadata.MediaMetaDao;
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
public final class DatabaseModule_ProvideMediaMetaDaoFactory implements Factory<MediaMetaDao> {
  private final Provider<DebForgeDatabase> dbProvider;

  private DatabaseModule_ProvideMediaMetaDaoFactory(Provider<DebForgeDatabase> dbProvider) {
    this.dbProvider = dbProvider;
  }

  @Override
  public MediaMetaDao get() {
    return provideMediaMetaDao(dbProvider.get());
  }

  public static DatabaseModule_ProvideMediaMetaDaoFactory create(
      Provider<DebForgeDatabase> dbProvider) {
    return new DatabaseModule_ProvideMediaMetaDaoFactory(dbProvider);
  }

  public static MediaMetaDao provideMediaMetaDao(DebForgeDatabase db) {
    return Preconditions.checkNotNullFromProvides(DatabaseModule.INSTANCE.provideMediaMetaDao(db));
  }
}
