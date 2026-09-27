package com.abhinavxt.debforge.data.backup;

import com.abhinavxt.debforge.data.follow.FollowStore;
import com.abhinavxt.debforge.data.prefs.SettingsStore;
import com.abhinavxt.debforge.data.prefs.TokenStore;
import com.squareup.moshi.Moshi;
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
public final class BackupManager_Factory implements Factory<BackupManager> {
  private final Provider<SettingsStore> settingsProvider;

  private final Provider<TokenStore> tokensProvider;

  private final Provider<FollowStore> followsProvider;

  private final Provider<Moshi> moshiProvider;

  private BackupManager_Factory(Provider<SettingsStore> settingsProvider,
      Provider<TokenStore> tokensProvider, Provider<FollowStore> followsProvider,
      Provider<Moshi> moshiProvider) {
    this.settingsProvider = settingsProvider;
    this.tokensProvider = tokensProvider;
    this.followsProvider = followsProvider;
    this.moshiProvider = moshiProvider;
  }

  @Override
  public BackupManager get() {
    return newInstance(settingsProvider.get(), tokensProvider.get(), followsProvider.get(), moshiProvider.get());
  }

  public static BackupManager_Factory create(Provider<SettingsStore> settingsProvider,
      Provider<TokenStore> tokensProvider, Provider<FollowStore> followsProvider,
      Provider<Moshi> moshiProvider) {
    return new BackupManager_Factory(settingsProvider, tokensProvider, followsProvider, moshiProvider);
  }

  public static BackupManager newInstance(SettingsStore settings, TokenStore tokens,
      FollowStore follows, Moshi moshi) {
    return new BackupManager(settings, tokens, follows, moshi);
  }
}
