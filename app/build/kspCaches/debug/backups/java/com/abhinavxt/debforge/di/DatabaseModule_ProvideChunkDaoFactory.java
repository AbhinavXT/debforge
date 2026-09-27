package com.abhinavxt.debforge.di;

import com.abhinavxt.debforge.data.local.ChunkDao;
import com.abhinavxt.debforge.data.local.DebForgeDatabase;
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
public final class DatabaseModule_ProvideChunkDaoFactory implements Factory<ChunkDao> {
  private final Provider<DebForgeDatabase> dbProvider;

  private DatabaseModule_ProvideChunkDaoFactory(Provider<DebForgeDatabase> dbProvider) {
    this.dbProvider = dbProvider;
  }

  @Override
  public ChunkDao get() {
    return provideChunkDao(dbProvider.get());
  }

  public static DatabaseModule_ProvideChunkDaoFactory create(
      Provider<DebForgeDatabase> dbProvider) {
    return new DatabaseModule_ProvideChunkDaoFactory(dbProvider);
  }

  public static ChunkDao provideChunkDao(DebForgeDatabase db) {
    return Preconditions.checkNotNullFromProvides(DatabaseModule.INSTANCE.provideChunkDao(db));
  }
}
