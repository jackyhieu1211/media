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
package androidx.media3.extractor.text;

import androidx.media3.common.C;
import androidx.media3.common.text.Cue;
import androidx.media3.common.util.UnstableApi;
import com.google.common.collect.ImmutableList;
import java.util.List;

/** A subtitle consisting of timed {@link Cue}s. */
@UnstableApi
public interface Subtitle {

  /**
   * Returns the index of the first event that occurs after a given time (exclusive).
   *
   * @param timeUs The time in microseconds.
   * @return The index of the next event, or {@link C#INDEX_UNSET} if there are no events after the
   *     specified time.
   */
  int getNextEventTimeIndex(long timeUs);

  /**
   * Returns the number of event times, where events are defined as points in time at which the cues
   * returned by {@link #getCues(long)} changes.
   *
   * @return The number of event times.
   */
  int getEventTimeCount();

  /**
   * Returns the event time at a specified index.
   *
   * @param index The index of the event time to obtain.
   * @return The event time in microseconds.
   */
  long getEventTime(int index);

  /**
   * Retrieve the cues that should be displayed at a given time.
   *
   * @param timeUs The time in microseconds.
   * @return A list of cues that should be displayed, possibly empty.
   */
  List<Cue> getCues(long timeUs);

  /** Converts the current instance to a list of {@link CuesWithTiming} representing it. */
  default ImmutableList<CuesWithTiming> toCuesWithTimingList() {
    ImmutableList.Builder<CuesWithTiming> allCues = ImmutableList.builder();
    for (int i = 0; i < getEventTimeCount(); i++) {
      long startTimeUs = getEventTime(i);
      List<Cue> cuesForThisStartTime = getCues(startTimeUs);
      if (cuesForThisStartTime.isEmpty() && i != 0) {
        // An empty cue list has already been implicitly encoded in the duration of the previous
        // sample (unless there was no previous sample).
        continue;
      }
      long durationUs =
          i < getEventTimeCount() - 1 ? getEventTime(i + 1) - getEventTime(i) : C.TIME_UNSET;
      allCues.add(new CuesWithTiming(cuesForThisStartTime, startTimeUs, durationUs));
    }
    return allCues.build();
  }
}
