package com.abhinavxt.debforge.ui.browse;

import android.content.Context;
import com.abhinavxt.debforge.data.follow.FollowStore;
import com.abhinavxt.debforge.data.local.DownloadDao;
import com.abhinavxt.debforge.data.metadata.MetadataRepository;
import com.abhinavxt.debforge.data.playback.PlaybackPositions;
import com.abhinavxt.debforge.data.prefs.SettingsStore;
import com.abhinavxt.debforge.data.repository.AuthRepository;
import com.abhinavxt.debforge.data.repository.DownloadsRepository;
import com.abhinavxt.debforge.download.DownloadController;
import com.abhinavxt.debforge.download.follow.FollowChecker;
import com.abhinavxt.debforge.download.watch.ReadyWatcher;
import com.abhinavxt.debforge.player.SubtitleResolver;
import com.abhinavxt.debforge.player.UpNext;
import com.abhinavxt.debforge.ui.add.ProviderEvents;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.Provider;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;

@ScopeMetadata
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
public final class BrowseViewModel_Factory implements Factory<BrowseViewModel> {
  private final Provider<DownloadsRepository> downloadsRepositoryProvider;

  private final Provider<DownloadController> downloadControllerProvider;

  private final Provider<SettingsStore> settingsStoreProvider;

  private final Provider<AuthRepository> authProvider;

  private final Provider<MetadataRepository> metadataProvider;

  private final Provider<ReadyWatcher> watcherProvider;

  private final Provider<Context> appContextProvider;

  private final Provider<ProviderEvents> providerEventsProvider;

  private final Provider<DownloadDao> downloadDaoProvider;

  private final Provider<FollowStore> followStoreProvider;

  private final Provider<FollowChecker> followCheckerProvider;

  private final Provider<PlaybackPositions> playbackPositionsProvider;

  private final Provider<SubtitleResolver> subtitleResolverProvider;

  private final Provider<UpNext> upNextProvider;

  private BrowseViewModel_Factory(Provider<DownloadsRepository> downloadsRepositoryProvider,
      Provider<DownloadController> downloadControllerProvider,
      Provider<SettingsStore> settingsStoreProvider, Provider<AuthRepository> authProvider,
      Provider<MetadataRepository> metadataProvider, Provider<ReadyWatcher> watcherProvider,
      Provider<Context> appContextProvider, Provider<ProviderEvents> providerEventsProvider,
      Provider<DownloadDao> downloadDaoProvider, Provider<FollowStore> followStoreProvider,
      Provider<FollowChecker> followCheckerProvider,
      Provider<PlaybackPositions> playbackPositionsProvider,
      Provider<SubtitleResolver> subtitleResolverProvider, Provider<UpNext> upNextProvider) {
    this.downloadsRepositoryProvider = downloadsRepositoryProvider;
    this.downloadControllerProvider = downloadControllerProvider;
    this.settingsStoreProvider = settingsStoreProvider;
    this.authProvider = authProvider;
    this.metadataProvider = metadataProvider;
    this.watcherProvider = watcherProvider;
    this.appContextProvider = appContextProvider;
    this.providerEventsProvider = providerEventsProvider;
    this.downloadDaoProvider = downloadDaoProvider;
    this.followStoreProvider = followStoreProvider;
    this.followCheckerProvider = followCheckerProvider;
    this.playbackPositionsProvider = playbackPositionsProvider;
    this.subtitleResolverProvider = subtitleResolverProvider;
    this.upNextProvider = upNextProvider;
  }

  @Override
  public BrowseViewModel get() {
    return newInstance(downloadsRepositoryProvider.get(), downloadControllerProvider.get(), settingsStoreProvider.get(), authProvider.get(), metadataProvider.get(), watcherProvider.get(), appContextProvider.get(), providerEventsProvider.get(), downloadDaoProvider.get(), followStoreProvider.get(), followCheckerProvider.get(), playbackPositionsProvider.get(), subtitleResolverProvider.get(), upNextProvider.get());
  }

  public static BrowseViewModel_Factory create(
      Provider<DownloadsRepository> downloadsRepositoryProvider,
      Provider<DownloadController> downloadControllerProvider,
      Provider<SettingsStore> settingsStoreProvider, Provider<AuthRepository> authProvider,
      Provider<MetadataRepository> metadataProvider, Provider<ReadyWatcher> watcherProvider,
      Provider<Context> appContextProvider, Provider<ProviderEvents> providerEventsProvider,
      Provider<DownloadDao> downloadDaoProvider, Provider<FollowStore> followStoreProvider,
      Provider<FollowChecker> followCheckerProvider,
      Provider<PlaybackPositions> playbackPositionsProvider,
      Provider<SubtitleResolver> subtitleResolverProvider, Provider<UpNext> upNextProvider) {
    return new BrowseViewModel_Factory(downloadsRepositoryProvider, downloadControllerProvider, settingsStoreProvider, authProvider, metadataProvider, watcherProvider, appContextProvider, providerEventsProvider, downloadDaoProvider, followStoreProvider, followCheckerProvider, playbackPositionsProvider, subtitleResolverProvider, upNextProvider);
  }

  public static BrowseViewModel newInstance(DownloadsRepository downloadsRepository,
      DownloadController downloadController, SettingsStore settingsStore, AuthRepository auth,
      MetadataRepository metadata, ReadyWatcher watcher, Context appContext,
      ProviderEvents providerEvents, DownloadDao downloadDao, FollowStore followStore,
      FollowChecker followChecker, PlaybackPositions playbackPositions,
      SubtitleResolver subtitleResolver, UpNext upNext) {
    return new BrowseViewModel(downloadsRepository, downloadController, settingsStore, auth, metadata, watcher, appContext, providerEvents, downloadDao, followStore, followChecker, playbackPositions, subtitleResolver, upNext);
  }
}
