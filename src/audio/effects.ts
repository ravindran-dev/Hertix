export const EQ_FREQUENCIES = [
  31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000,
];
export const EQ_PRESETS: Record<string, number[]> = {
  Flat: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
  "Bass Boost": [6, 5, 4, 2, 0, 0, 0, 0, 0, 0],
  Rock: [4, 3, 1, -1, -2, 1, 3, 4, 4, 3],
  Pop: [-1, 1, 3, 4, 3, 1, -1, -1, 1, 2],
  Classical: [3, 2, 0, -1, -1, 0, 1, 2, 3, 4],
  Jazz: [3, 2, 1, 2, -1, -1, 1, 2, 3, 3],
  Vocal: [-3, -2, -1, 1, 3, 4, 3, 1, 0, -1],
  Electronic: [5, 4, 2, 0, -2, 1, 0, 2, 4, 5],
};
export interface EffectSettings {
  eqEnabled: boolean;
  eqPreset: string;
  eq: number[];
  bass: number;
  stereoWidth: number;
  balance: number;
  normalization: boolean;
  crossfade: number;
  gapless: boolean;
  resume: boolean;
}
export const DEFAULT_EFFECTS: EffectSettings = {
  eqEnabled: false,
  eqPreset: "Flat",
  eq: [...EQ_PRESETS.Flat],
  bass: 0,
  stereoWidth: 1,
  balance: 0,
  normalization: false,
  crossfade: 0,
  gapless: true,
  resume: true,
};
export function equalPower(progress: number): [number, number] {
  const p = Math.max(0, Math.min(1, progress));
  return [Math.cos((p * Math.PI) / 2), Math.sin((p * Math.PI) / 2)];
}
/** One graph shared by both streaming decks. No full-file decode or audio copies. */
export class AudioEffects {
  readonly context: AudioContext;
  private filters: BiquadFilterNode[];
  private bass: BiquadFilterNode;
  private preamp: GainNode;
  private output: GainNode;
  private panner: StereoPannerNode;
  private matrix: GainNode[];
  private voices = new Map<
    HTMLAudioElement,
    {
      source: MediaElementAudioSourceNode;
      fade: GainNode;
      normalization: GainNode;
    }
  >();
  constructor() {
    this.context = new AudioContext();
    const c = this.context;
    this.preamp = c.createGain();
    this.preamp.channelCount = 2;
    this.preamp.channelCountMode = "explicit";
    this.filters = EQ_FREQUENCIES.map((hz) => {
      const f = c.createBiquadFilter();
      f.type = "peaking";
      f.frequency.value = hz;
      f.Q.value = 1;
      return f;
    });
    this.bass = c.createBiquadFilter();
    this.bass.type = "lowshelf";
    this.bass.frequency.value = 100;
    let previous: AudioNode = this.preamp;
    for (const f of [...this.filters, this.bass]) {
      previous.connect(f);
      previous = f;
    }
    const split = c.createChannelSplitter(2),
      merge = c.createChannelMerger(2);
    previous.connect(split);
    this.matrix = Array.from({ length: 4 }, () => c.createGain());
    split.connect(this.matrix[0], 0);
    split.connect(this.matrix[1], 1);
    split.connect(this.matrix[2], 0);
    split.connect(this.matrix[3], 1);
    this.matrix[0].connect(merge, 0, 0);
    this.matrix[1].connect(merge, 0, 0);
    this.matrix[2].connect(merge, 0, 1);
    this.matrix[3].connect(merge, 0, 1);
    this.panner = c.createStereoPanner();
    this.output = c.createGain();
    merge.connect(this.panner);
    this.panner.connect(this.output);
    this.output.connect(c.destination);
  }
  attach(audio: HTMLAudioElement) {
    if (this.voices.has(audio)) return;
    const c = this.context,
      source = c.createMediaElementSource(audio),
      fade = c.createGain(),
      normalization = c.createGain();
    source.connect(normalization);
    normalization.connect(fade);
    fade.connect(this.preamp);
    this.voices.set(audio, { source, fade, normalization });
  }
  configure(settings: EffectSettings, volume: number) {
    const c = this.context,
      ramp = (p: AudioParam, v: number) =>
        p.setTargetAtTime(v, c.currentTime, 0.025);
    const bands = settings.eqEnabled ? settings.eq : EQ_PRESETS.Flat;
    this.filters.forEach((f, i) =>
      ramp(f.gain, Math.max(-12, Math.min(12, bands[i] || 0))),
    );
    ramp(this.bass.gain, settings.bass);
    // Reserve headroom for combined boosts before the filters.
    ramp(
      this.preamp.gain,
      10 ** (-(Math.max(0, ...bands) + Math.max(0, settings.bass)) / 20),
    );
    const width = settings.stereoWidth;
    [
      (1 + width) / 2,
      (1 - width) / 2,
      (1 - width) / 2,
      (1 + width) / 2,
    ].forEach((v, i) => ramp(this.matrix[i].gain, v));
    ramp(this.panner.pan, settings.balance);
    ramp(this.output.gain, volume);
  }
  gain(audio: HTMLAudioElement, value: number) {
    const p = this.voices.get(audio)?.fade.gain;
    if (p) {
      p.cancelScheduledValues(this.context.currentTime);
      p.setValueAtTime(value, this.context.currentTime);
    }
  }
  normalize(audio: HTMLAudioElement, db: number) {
    this.voices
      .get(audio)
      ?.normalization.gain.setTargetAtTime(
        10 ** (db / 20),
        this.context.currentTime,
        0.1,
      );
  }
  async resume() {
    if (this.context.state === "suspended") await this.context.resume();
  }
  async close() {
    this.voices.forEach((v) => v.source.disconnect());
    this.voices.clear();
    await this.context.close();
  }
}
