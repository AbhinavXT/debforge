package com.abhinavxt.debforge.player;

import com.abhinavxt.debforge.data.playback.PlaybackPositions;
import com.abhinavxt.debforge.data.prefs.SettingsStore;
import com.abhinavxt.debforge.data.repository.DownloadsRepository;
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
public final class PlayerActivity_MembersInjector implements MembersInjector<PlayerActivity> {
  private final Provider<PlaybackPositions> positionsProvider;

  private final Provider<UpNext> upNextProvider;

  private final Provider<SubtitleResolver> subtitleResolverProvider;

  private final Provider<DownloadsRepository> repositoryProvider;

  private final Provider<SettingsStore> settingsProvider;

  private PlayerActivity_MembersInjector(Provider<PlaybackPositions> positionsProvider,
      Provider<UpNext> upNextProvider, Provider<SubtitleResolver> subtitleResolverProvider,
      Provider<DownloadsRepository> repositoryProvider, Provider<SettingsStore> settingsProvider) {
    this.positionsProvider = positionsProvider;
    this.upNextProvider = upNextProvider;
    this.subtitleResolverProvider = subtitleResolverProvider;
    this.repositoryProvider = repositoryProvider;
    this.settingsProvider = settingsProvider;
  }

  @Override
  public void injectMembers(PlayerActivity instance) {
    injectPositions(instance, positionsProvider.get());
    injectUpNext(instance, upNextProvider.get());
    injectSubtitleResolver(instance, subtitleResolverProvider.get());
    injectRepository(instance, repositoryProvider.get());
    injectSettings(instance, settingsProvider.get());
  }

  public static MembersInjector<PlayerActivity> create(
      Provider<PlaybackPositions> positionsProvider, Provider<UpNext> upNextProvider,
      Provider<SubtitleResolver> subtitleResolverProvider,
      Provider<DownloadsRepository> repositoryProvider, Provider<SettingsStore> settingsProvider) {
    return new PlayerActivity_MembersInjector(positionsProvider, upNextProvider, subtitleResolverProvider, repositoryProvider, settingsProvider);
  }

  @InjectedFieldSignature("com.abhinavxt.debforge.player.PlayerActivity.positions")
  public static void injectPositions(PlayerActivity instance, PlaybackPositions positions) {
    instance.positions = positions;
  }

  @InjectedFieldSignature("com.abhinavxt.debforge.player.PlayerActivity.upNext")
  public static void injectUpNext(PlayerActivity instance, UpNext upNext) {
    instance.upNext = upNext;
  }

  @InjectedFieldSignature("com.abhinavxt.debforge.player.PlayerActivity.subtitleResolver")
  public static void injectSubtitleResolver(PlayerActivity instance,
      SubtitleResolver subtitleResolver) {
    instance.subtitleResolver = subtitleResolver;
  }

  @InjectedFieldSignature("com.abhinavxt.debforge.player.PlayerActivity.repository")
  public static void injectRepository(PlayerActivity instance, DownloadsRepository repository) {
    instance.repository = repository;
  }

  @InjectedFieldSignature("com.abhinavxt.debforge.player.PlayerActivity.settings")
  public static void injectSettings(PlayerActivity instance, SettingsStore settings) {
    instance.settings = settings;
  }
}
