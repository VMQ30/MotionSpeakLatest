import { NativeModules, NativeEventEmitter, Platform } from 'react-native';

const { SiglaBridge } = NativeModules;
const eventEmitter = SiglaBridge ? new NativeEventEmitter(SiglaBridge) : null;

export interface TranslationResultEvent {
  word: string;
  confidence: number;
  filipino?: string;
  gestureType: string;
}

export interface DetectionStatusEvent {
  status: string;
  frames: number;
  bufferPercent: number;
  velocity: string;
  streak: number;
  handsWarning: boolean;
}

export interface TapProgressEvent {
  elapsedSeconds: number;
  maxSeconds: number;
  progressPercent: number;
  isRecording: boolean;
}

export interface VideoDownloadProgressEvent {
  completed: number;
  total: number;
  status: 'downloading' | 'finished' | 'failed' | 'cancelled';
  message?: string;
}

class NativeBridgeService {
  public speak(text: string, volume?: number, voiceType?: string) {
    if (SiglaBridge?.speak) {
      SiglaBridge.speak(text, volume ?? 80, voiceType ?? 'female');
    }
  }

  public flipCamera() {
    if (SiglaBridge?.flipCamera) {
      SiglaBridge.flipCamera();
    }
  }

  public setCameraFacing(facing: 'front' | 'back') {
    if (SiglaBridge?.setCameraFacing) {
      SiglaBridge.setCameraFacing(facing);
    }
  }

  public startRecognition(mode: 'tap' | 'live', vocabulary: 'words' | 'letters') {
    if (SiglaBridge?.startRecognition) {
      SiglaBridge.startRecognition(mode, vocabulary);
    }
  }

  public stopRecognition() {
    if (SiglaBridge?.stopRecognition) {
      SiglaBridge.stopRecognition();
    }
  }

  public startTapRecording() {
    if (SiglaBridge?.startTapRecording) {
      SiglaBridge.startTapRecording();
    }
  }

  public stopTapRecording() {
    if (SiglaBridge?.stopTapRecording) {
      SiglaBridge.stopTapRecording();
    }
  }

  public triggerSOS() {
    if (SiglaBridge?.triggerSOS) {
      SiglaBridge.triggerSOS();
    }
  }

  public async getModelStatus(): Promise<{ version: string | null; hasLocalModel: boolean }> {
    if (SiglaBridge?.getModelStatus) {
      return await SiglaBridge.getModelStatus();
    }
    return { version: '1.10', hasLocalModel: true };
  }

  public async checkAndUpdateModel(): Promise<{ updated: boolean; version?: string }> {
    if (SiglaBridge?.checkAndUpdateModel) {
      return await SiglaBridge.checkAndUpdateModel();
    }
    return { updated: false };
  }

  public downloadDemoVideo(url: string, wordId: number) {
    if (SiglaBridge?.downloadDemoVideo) {
      SiglaBridge.downloadDemoVideo(url, wordId);
    }
  }

  public downloadAllDemoVideos() {
    if (SiglaBridge?.downloadAllDemoVideos) {
      SiglaBridge.downloadAllDemoVideos();
    }
  }

  public cancelDownloadAllVideos() {
    if (SiglaBridge?.cancelDownloadAllVideos) {
      SiglaBridge.cancelDownloadAllVideos();
    }
  }

  // Event Subscriptions
  public onTranslationResult(callback: (event: TranslationResultEvent) => void) {
    if (!eventEmitter) return { remove: () => {} };
    const subscription = eventEmitter.addListener('onTranslationResult', callback);
    return subscription;
  }

  public onDetectionStatus(callback: (event: DetectionStatusEvent) => void) {
    if (!eventEmitter) return { remove: () => {} };
    const subscription = eventEmitter.addListener('onDetectionStatus', callback);
    return subscription;
  }

  public onTapProgress(callback: (event: TapProgressEvent) => void) {
    if (!eventEmitter) return { remove: () => {} };
    const subscription = eventEmitter.addListener('onTapProgress', callback);
    return subscription;
  }

  public onVideoDownloadProgress(callback: (event: VideoDownloadProgressEvent) => void) {
    if (!eventEmitter) return { remove: () => {} };
    const subscription = eventEmitter.addListener('onVideoDownloadProgress', callback);
    return subscription;
  }
}

export const NativeBridge = new NativeBridgeService();
