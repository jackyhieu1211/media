/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.audio;

import static androidx.media3.common.util.Assertions.checkNotNull;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.provider.Settings.Global;
import android.util.Pair;
import androidx.annotation.DoNotInline;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import java.util.Arrays;

/** Represents the set of audio formats that a device is capable of playing. */
@UnstableApi
public final class AudioCapabilities {

  // TODO(internal b/283945513): Have separate default max channel counts in `AudioCapabilities`
  // for PCM and compressed audio.
  private static final int DEFAULT_MAX_CHANNEL_COUNT = 10;
  @VisibleForTesting /* package */ static final int DEFAULT_SAMPLE_RATE_HZ = 48_000;

  /** The minimum audio capabilities supported by all devices. */
  public static final AudioCapabilities DEFAULT_AUDIO_CAPABILITIES =
      new AudioCapabilities(new int[] {AudioFormat.ENCODING_PCM_16BIT}, DEFAULT_MAX_CHANNEL_COUNT);

  /** Encodings supported when the device specifies external surround sound. */
  private static final ImmutableList<Integer> EXTERNAL_SURROUND_SOUND_ENCODINGS =
      ImmutableList.of(
          AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_E_AC3);

  /**
   * All surround sound encodings that a device may be capable of playing mapped to a maximum
   * channel count.
   */
  private static final ImmutableMap<Integer, Integer> ALL_SURROUND_ENCODINGS_AND_MAX_CHANNELS =
      new ImmutableMap.Builder<Integer, Integer>()
          .put(C.ENCODING_AC3, 6)
          .put(C.ENCODING_AC4, 6)
          .put(C.ENCODING_DTS, 6)
          .put(C.ENCODING_DTS_UHD_P2, 10)
          .put(C.ENCODING_E_AC3_JOC, 6)
          .put(C.ENCODING_E_AC3, 8)
          .put(C.ENCODING_DTS_HD, 8)
          .put(C.ENCODING_DOLBY_TRUEHD, 8)
          .buildOrThrow();

  /** Global settings key for devices that can specify external surround sound. */
  private static final String EXTERNAL_SURROUND_SOUND_KEY = "external_surround_sound_enabled";

  /**
   * Returns the current audio capabilities for the device.
   *
   * @param context A context for obtaining the current audio capabilities.
   * @return The current audio capabilities for the device.
   */
  @SuppressWarnings("InlinedApi")
  public static AudioCapabilities getCapabilities(Context context) {
    Intent intent =
        context.registerReceiver(
            /* receiver= */ null, new IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG));
    return getCapabilities(context, intent);
  }

  @SuppressLint("InlinedApi")
  /* package */ static AudioCapabilities getCapabilities(Context context, @Nullable Intent intent) {
    // If a connection to Bluetooth device is detected, we only return the minimum capabilities that
    // is supported by all the devices.
    if (Util.SDK_INT >= 23 && Api23.isBluetoothConnected(context)) {
      return DEFAULT_AUDIO_CAPABILITIES;
    }

    ImmutableSet.Builder<Integer> supportedEncodings = new ImmutableSet.Builder<>();
    if (deviceMaySetExternalSurroundSoundGlobalSetting()
        && Global.getInt(context.getContentResolver(), EXTERNAL_SURROUND_SOUND_KEY, 0) == 1) {
      supportedEncodings.addAll(EXTERNAL_SURROUND_SOUND_ENCODINGS);
    }
    // AudioTrack.isDirectPlaybackSupported returns true for encodings that are supported for audio
    // offload, as well as for encodings we want to list for passthrough mode. Therefore we only use
    // it on TV and automotive devices, which generally shouldn't support audio offload for surround
    // encodings.
    if (Util.SDK_INT >= 29 && (Util.isTv(context) || Util.isAutomotive(context))) {
      supportedEncodings.addAll(Api29.getDirectPlaybackSupportedEncodings());
      return new AudioCapabilities(
          Ints.toArray(supportedEncodings.build()), DEFAULT_MAX_CHANNEL_COUNT);
    }

    if (intent != null && intent.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 0) == 1) {
      @Nullable int[] encodingsFromExtra = intent.getIntArrayExtra(AudioManager.EXTRA_ENCODINGS);
      if (encodingsFromExtra != null) {
        supportedEncodings.addAll(Ints.asList(encodingsFromExtra));
      }
      return new AudioCapabilities(
          Ints.toArray(supportedEncodings.build()),
          intent.getIntExtra(
              AudioManager.EXTRA_MAX_CHANNEL_COUNT, /* defaultValue= */ DEFAULT_MAX_CHANNEL_COUNT));
    }

    ImmutableSet<Integer> supportedEncodingsSet = supportedEncodings.build();
    if (!supportedEncodingsSet.isEmpty()) {
      return new AudioCapabilities(
          Ints.toArray(supportedEncodingsSet), /* maxChannelCount= */ DEFAULT_MAX_CHANNEL_COUNT);
    }
    return DEFAULT_AUDIO_CAPABILITIES;
  }

  /**
   * Returns the global settings {@link Uri} used by the device to specify external surround sound,
   * or null if the device does not support this functionality.
   */
  @Nullable
  /* package */ static Uri getExternalSurroundSoundGlobalSettingUri() {
    return deviceMaySetExternalSurroundSoundGlobalSetting()
        ? Global.getUriFor(EXTERNAL_SURROUND_SOUND_KEY)
        : null;
  }

  private final int[] supportedEncodings;
  private final int maxChannelCount;

  /**
   * Constructs new audio capabilities based on a set of supported encodings and a maximum channel
   * count.
   *
   * <p>Applications should generally call {@link #getCapabilities(Context)} to obtain an instance
   * based on the capabilities advertised by the platform, rather than calling this constructor.
   *
   * @param supportedEncodings Supported audio encodings from {@link android.media.AudioFormat}'s
   *     {@code ENCODING_*} constants. Passing {@code null} indicates that no encodings are
   *     supported.
   * @param maxChannelCount The maximum number of audio channels that can be played simultaneously.
   */
  public AudioCapabilities(@Nullable int[] supportedEncodings, int maxChannelCount) {
    if (supportedEncodings != null) {
      this.supportedEncodings = Arrays.copyOf(supportedEncodings, supportedEncodings.length);
      Arrays.sort(this.supportedEncodings);
    } else {
      this.supportedEncodings = new int[0];
    }
    this.maxChannelCount = maxChannelCount;
  }

  /**
   * Returns whether this device supports playback of the specified audio {@code encoding}.
   *
   * @param encoding One of {@link C.Encoding}'s {@code ENCODING_*} constants.
   * @return Whether this device supports playback the specified audio {@code encoding}.
   */
  public boolean supportsEncoding(@C.Encoding int encoding) {
    return Arrays.binarySearch(supportedEncodings, encoding) >= 0;
  }

  /** Returns the maximum number of channels the device can play at the same time. */
  public int getMaxChannelCount() {
    return maxChannelCount;
  }

  /** Returns whether the device can do passthrough playback for {@code format}. */
  public boolean isPassthroughPlaybackSupported(Format format) {
    return getEncodingAndChannelConfigForPassthrough(format) != null;
  }

  /**
   * Returns the encoding and channel config to use when configuring an {@link AudioTrack} in
   * passthrough mode for the specified {@link Format}. Returns {@code null} if passthrough of the
   * format is unsupported.
   *
   * @param format The {@link Format}.
   * @return The encoding and channel config to use, or {@code null} if passthrough of the format is
   *     unsupported.
   */
  @Nullable
  public Pair<Integer, Integer> getEncodingAndChannelConfigForPassthrough(Format format) {
    @C.Encoding
    int encoding = MimeTypes.getEncoding(checkNotNull(format.sampleMimeType), format.codecs);
    // Check that this is an encoding known to work for passthrough. This avoids trying to use
    // passthrough with an encoding where the device/app reports it's capable but it is untested or
    // known to be broken (for example AAC-LC).
    if (!ALL_SURROUND_ENCODINGS_AND_MAX_CHANNELS.containsKey(encoding)) {
      return null;
    }

    if (encoding == C.ENCODING_E_AC3_JOC && !supportsEncoding(C.ENCODING_E_AC3_JOC)) {
      // E-AC3 receivers support E-AC3 JOC streams (but decode only the base layer).
      encoding = C.ENCODING_E_AC3;
    } else if ((encoding == C.ENCODING_DTS_HD && !supportsEncoding(C.ENCODING_DTS_HD))
        || (encoding == C.ENCODING_DTS_UHD_P2 && !supportsEncoding(C.ENCODING_DTS_UHD_P2))) {
      // DTS receivers support DTS-HD streams (but decode only the core layer).
      encoding = C.ENCODING_DTS;
    }
    if (!supportsEncoding(encoding)) {
      return null;
    }
    int channelCount;
    if (format.channelCount == Format.NO_VALUE || encoding == C.ENCODING_E_AC3_JOC) {
      // In HLS chunkless preparation, the format channel count and sample rate may be unset. See
      // https://github.com/google/ExoPlayer/issues/10204 and b/222127949 for more details.
      // For E-AC3 JOC, the format is object based so the format channel count is arbitrary.
      int sampleRate =
          format.sampleRate != Format.NO_VALUE ? format.sampleRate : DEFAULT_SAMPLE_RATE_HZ;
      channelCount = getMaxSupportedChannelCountForPassthrough(encoding, sampleRate);
    } else {
      channelCount = format.channelCount;
      // Some DTS:X TVs reports ACTION_HDMI_AUDIO_PLUG.EXTRA_MAX_CHANNEL_COUNT as 8
      // instead of 10. See https://github.com/androidx/media/issues/396
      if (format.sampleMimeType.equals(MimeTypes.AUDIO_DTS_X)) {
        if (channelCount > 10) {
          return null;
        }
      } else if (channelCount > maxChannelCount) {
        return null;
      }
    }
    int channelConfig = getChannelConfigForPassthrough(channelCount);
    if (channelConfig == AudioFormat.CHANNEL_INVALID) {
      return null;
    }
    return Pair.create(encoding, channelConfig);
  }

  @Override
  public boolean equals(@Nullable Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof AudioCapabilities)) {
      return false;
    }
    AudioCapabilities audioCapabilities = (AudioCapabilities) other;
    return Arrays.equals(supportedEncodings, audioCapabilities.supportedEncodings)
        && maxChannelCount == audioCapabilities.maxChannelCount;
  }

  @Override
  public int hashCode() {
    return maxChannelCount + 31 * Arrays.hashCode(supportedEncodings);
  }

  @Override
  public String toString() {
    return "AudioCapabilities[maxChannelCount="
        + maxChannelCount
        + ", supportedEncodings="
        + Arrays.toString(supportedEncodings)
        + "]";
  }

  private static boolean deviceMaySetExternalSurroundSoundGlobalSetting() {
    return Util.SDK_INT >= 17
        && ("Amazon".equals(Util.MANUFACTURER) || "Xiaomi".equals(Util.MANUFACTURER));
  }

  /**
   * Returns the maximum number of channels supported for passthrough playback of audio in the given
   * encoding, or {@code 0} if the format is unsupported.
   */
  private static int getMaxSupportedChannelCountForPassthrough(
      @C.Encoding int encoding, int sampleRate) {
    // From API 29 we can get the channel count from the platform, but before then there is no way
    // to query the platform so we assume the channel count matches the maximum channel count per
    // audio encoding spec.
    if (Util.SDK_INT >= 29) {
      return Api29.getMaxSupportedChannelCountForPassthrough(encoding, sampleRate);
    }
    return checkNotNull(ALL_SURROUND_ENCODINGS_AND_MAX_CHANNELS.getOrDefault(encoding, 0));
  }

  private static int getChannelConfigForPassthrough(int channelCount) {
    if (Util.SDK_INT <= 28) {
      // In passthrough mode the channel count used to configure the audio track doesn't affect how
      // the stream is handled, except that some devices do overly-strict channel configuration
      // checks. Therefore we override the channel count so that a known-working channel
      // configuration is chosen in all cases. See [Internal: b/29116190].
      if (channelCount == 7) {
        channelCount = 8;
      } else if (channelCount == 3 || channelCount == 4 || channelCount == 5) {
        channelCount = 6;
      }
    }

    // Workaround for Nexus Player not reporting support for mono passthrough. See
    // [Internal: b/34268671].
    if (Util.SDK_INT <= 26 && "fugu".equals(Util.DEVICE) && channelCount == 1) {
      channelCount = 2;
    }

    return Util.getAudioTrackChannelConfig(channelCount);
  }

  @RequiresApi(23)
  private static final class Api23 {
    private Api23() {}

    @DoNotInline
    public static final boolean isBluetoothConnected(Context context) {
      AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
      AudioDeviceInfo[] audioDeviceInfos =
          checkNotNull(audioManager).getDevices(AudioManager.GET_DEVICES_OUTPUTS);
      ImmutableSet<Integer> allBluetoothDeviceTypesSet = getAllBluetoothDeviceTypes();
      for (int i = 0; i < audioDeviceInfos.length; i++) {
        if (allBluetoothDeviceTypesSet.contains(audioDeviceInfos[i].getType())) {
          return true;
        }
      }
      return false;
    }

    /**
     * Returns all the possible bluetooth device types that can be returned by {@link
     * AudioDeviceInfo#getType()}.
     *
     * <p>The types {@link AudioDeviceInfo#TYPE_BLUETOOTH_A2DP} and {@link
     * AudioDeviceInfo#TYPE_BLUETOOTH_SCO} are included from API 23. And the types {@link
     * AudioDeviceInfo#TYPE_BLE_HEADSET} and {@link AudioDeviceInfo#TYPE_BLE_SPEAKER} are added from
     * API 31. And the type {@link AudioDeviceInfo#TYPE_BLE_BROADCAST} is added from API 33.
     */
    @DoNotInline
    private static final ImmutableSet<Integer> getAllBluetoothDeviceTypes() {
      ImmutableSet.Builder<Integer> allBluetoothDeviceTypes =
          new ImmutableSet.Builder<Integer>()
              .add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
      if (Util.SDK_INT >= 31) {
        allBluetoothDeviceTypes.add(
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER);
      }
      if (Util.SDK_INT >= 33) {
        allBluetoothDeviceTypes.add(AudioDeviceInfo.TYPE_BLE_BROADCAST);
      }
      return allBluetoothDeviceTypes.build();
    }
  }

  @RequiresApi(29)
  private static final class Api29 {
    private static final AudioAttributes DEFAULT_AUDIO_ATTRIBUTES =
        new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .setFlags(0)
            .build();

    private Api29() {}

    @DoNotInline
    public static ImmutableList<Integer> getDirectPlaybackSupportedEncodings() {
      ImmutableList.Builder<Integer> supportedEncodingsListBuilder = ImmutableList.builder();
      for (int encoding : ALL_SURROUND_ENCODINGS_AND_MAX_CHANNELS.keySet()) {
        if (Util.SDK_INT < Util.getApiLevelThatAudioFormatIntroducedAudioEncoding(encoding)) {
          // Example: AudioFormat.ENCODING_DTS_UHD_P2 is supported only from API 34.
          continue;
        }
        if (AudioTrack.isDirectPlaybackSupported(
            new AudioFormat.Builder()
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(encoding)
                .setSampleRate(DEFAULT_SAMPLE_RATE_HZ)
                .build(),
            DEFAULT_AUDIO_ATTRIBUTES)) {
          supportedEncodingsListBuilder.add(encoding);
        }
      }
      supportedEncodingsListBuilder.add(AudioFormat.ENCODING_PCM_16BIT);
      return supportedEncodingsListBuilder.build();
    }

    /**
     * Returns the maximum number of channels supported for passthrough playback of audio in the
     * given format, or {@code 0} if the format is unsupported.
     */
    @DoNotInline
    public static int getMaxSupportedChannelCountForPassthrough(
        @C.Encoding int encoding, int sampleRate) {
      // TODO(internal b/234351617): Query supported channel masks directly once it's supported,
      // see also b/25994457.
      for (int channelCount = DEFAULT_MAX_CHANNEL_COUNT; channelCount > 0; channelCount--) {
        AudioFormat audioFormat =
            new AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelMask(Util.getAudioTrackChannelConfig(channelCount))
                .build();
        if (AudioTrack.isDirectPlaybackSupported(audioFormat, DEFAULT_AUDIO_ATTRIBUTES)) {
          return channelCount;
        }
      }
      return 0;
    }
  }
}
