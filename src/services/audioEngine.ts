// Web Audio API and Speech Processing Engine for Hermes Duplex

export class AudioEngine {
  private audioContext: AudioContext | null = null;
  private analyser: AnalyserNode | null = null;
  private micStream: MediaStream | null = null;
  private dataArray: Uint8Array | null = null;
  private recognition: any = null;
  private isListening = false;
  private onSpeechResultCallback: ((text: string) => void) | null = null;
  private onVolumeChangeCallback: ((volume: number) => void) | null = null;
  private animFrameId: number | null = null;

  async initMic(): Promise<boolean> {
    try {
      this.micStream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
          channelCount: 1,
        },
      });

      this.audioContext = new (window.AudioContext || (window as any).webkitAudioContext)({
        sampleRate: 24000,
      });

      const source = this.audioContext.createMediaStreamSource(this.micStream);
      this.analyser = this.audioContext.createAnalyser();
      this.analyser.fftSize = 64;
      this.analyser.smoothingTimeConstant = 0.8;
      source.connect(this.analyser);

      const bufferLength = this.analyser.frequencyBinCount;
      this.dataArray = new Uint8Array(bufferLength);

      this.startVolumeMonitoring();
      this.initSpeechRecognition();
      this.isListening = true;
      return true;
    } catch (err) {
      console.warn('Microphone access denied or audio context failed:', err);
      return false;
    }
  }

  private initSpeechRecognition() {
    const SpeechRecognition =
      (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (SpeechRecognition) {
      try {
        this.recognition = new SpeechRecognition();
        this.recognition.continuous = true;
        this.recognition.interimResults = true;
        this.recognition.lang = 'en-US';

        this.recognition.onresult = (event: any) => {
          let finalTranscript = '';
          for (let i = event.resultIndex; i < event.results.length; ++i) {
            if (event.results[i].isFinal) {
              finalTranscript += event.results[i][0].transcript;
            }
          }
          if (finalTranscript && this.onSpeechResultCallback) {
            this.onSpeechResultCallback(finalTranscript.trim());
          }
        };

        this.recognition.onerror = (e: any) => {
          console.warn('Speech recognition notice:', e.error);
        };

        this.recognition.start();
      } catch (e) {
        console.warn('Speech recognition start failed:', e);
      }
    }
  }

  private startVolumeMonitoring() {
    const checkVolume = () => {
      if (this.analyser && this.dataArray && this.isListening) {
        // @ts-ignore
        this.analyser.getByteFrequencyData(this.dataArray);
        let sum = 0;
        for (let i = 0; i < this.dataArray.length; i++) {
          sum += this.dataArray[i];
        }
        const avg = sum / this.dataArray.length / 255;
        if (this.onVolumeChangeCallback) {
          this.onVolumeChangeCallback(avg);
        }
      }
      this.animFrameId = requestAnimationFrame(checkVolume);
    };
    checkVolume();
  }

  onSpeech(cb: (text: string) => void) {
    this.onSpeechResultCallback = cb;
  }

  onVolume(cb: (volume: number) => void) {
    this.onVolumeChangeCallback = cb;
  }

  getFrequencyData(): Uint8Array | null {
    if (this.analyser && this.dataArray) {
      // @ts-ignore
      this.analyser.getByteFrequencyData(this.dataArray);
      return this.dataArray;
    }
    return null;
  }

  speak(text: string, onEnd?: () => void) {
    this.stopSpeaking();
    if ('speechSynthesis' in window) {
      const utterance = new SpeechSynthesisUtterance(text);
      utterance.rate = 1.05;
      utterance.pitch = 1.0;
      utterance.onend = () => {
        if (onEnd) onEnd();
      };
      window.speechSynthesis.speak(utterance);
    } else {
      setTimeout(() => {
        if (onEnd) onEnd();
      }, 2000);
    }
  }

  stopSpeaking() {
    if ('speechSynthesis' in window) {
      window.speechSynthesis.cancel();
    }
  }

  stopMic() {
    this.isListening = false;
    if (this.animFrameId) {
      cancelAnimationFrame(this.animFrameId);
      this.animFrameId = null;
    }
    if (this.recognition) {
      try {
        this.recognition.stop();
      } catch {}
      this.recognition = null;
    }
    if (this.micStream) {
      this.micStream.getTracks().forEach((track) => track.stop());
      this.micStream = null;
    }
    if (this.audioContext && this.audioContext.state !== 'closed') {
      try {
        this.audioContext.close();
      } catch {}
      this.audioContext = null;
    }
  }
}
