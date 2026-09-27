package com.abhinavxt.debforge.screenshots;

import com.abhinavxt.debforge.data.local.DownloadDao;
import com.abhinavxt.debforge.data.prefs.SettingsStore;
import com.abhinavxt.debforge.data.prefs.TokenStore;
import com.abhinavxt.debforge.data.usage.UsageDao;
import com.abhinavxt.debforge.download.DownloadProgressTracker;
import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import javax.annotation.processing.Generated;

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
public final class StoreScreenshots_MembersInjector implements MembersInjector<StoreScreenshots> {
  private final Provider<TokenStore> tokensProvider;

  private final Provider<SettingsStore> settingsProvider;

  private final Provider<DownloadDao> downloadsProvider;

  private final Provider<UsageDao> usageProvider;

  private final Provider<DownloadProgressTracker> progressProvider;

  private StoreScreenshots_MembersInjector(Provider<TokenStore> tokensProvider,
      Provider<SettingsStore> settingsProvider, Provider<DownloadDao> downloadsProvider,
      Provider<UsageDao> usageProvider, Provider<DownloadProgressTracker> progressProvider) {
    this.tokensProvider = tokensProvider;
    this.settingsProvider = settingsProvider;
    this.downloadsProvider = downloadsProvider;
    this.usageProvider = usageProvider;
    this.progressProvider = progressProvider;
  }

  @Override
  public void injectMembers(StoreScreenshots instance) {
    injectTokens(instance, tokensProvider.get());
    injectSettings(instance, settingsProvider.get());
    injectDownloads(instance, downloadsProvider.get());
    injectUsage(instance, usageProvider.get());
    injectProgress(instance, progressProvider.get());
  }

  public static MembersInjector<StoreScreenshots> create(Provider<TokenStore> tokensProvider,
      Provider<SettingsStore> settingsProvider, Provider<DownloadDao> downloadsProvider,
      Provider<UsageDao> usageProvider, Provider<DownloadProgressTracker> progressProvider) {
    return new StoreScreenshots_MembersInjector(tokensProvider, settingsProvider, downloadsProvider, usageProvider, progressProvider);
  }

  @InjectedFieldSignature("com.abhinavxt.debforge.screenshots.StoreScreenshots.tokens")
  public static void injectTokens(StoreScreenshots instance, TokenStore tokens) {
    instance.tokens = tokens;
  }

  @InjectedFieldSignature("com.abhinavxt.debforge.screenshots.StoreScreenshots.settings")
  public static void injectSettings(StoreScreenshots instance, SettingsStore settings) {
    instance.settings = settings;
  }

  @InjectedFieldSignature("com.abhinavxt.debforge.screenshots.StoreScreenshots.downloads")
  public static void injectDownloads(StoreScreenshots instance, DownloadDao downloads) {
    instance.downloads = downloads;
  }

  @InjectedFieldSignature("com.abhinavxt.debforge.screenshots.StoreScreenshots.usage")
  public static void injectUsage(StoreScreenshots instance, UsageDao usage) {
    instance.usage = usage;
  }

  @InjectedFieldSignature("com.abhinavxt.debforge.screenshots.StoreScreenshots.progress")
  public static void injectProgress(StoreScreenshots instance, DownloadProgressTracker progress) {
    instance.progress = progress;
  }
}
