import React, { useState, useEffect, useRef } from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  SafeAreaView,
  StyleSheet,
  Animated,
  Easing,
  Platform,
} from 'react-native';
import { CameraView } from '../components/CameraView';
import {
  IconBack,
  IconCameraFlip,
  IconChat,
  IconSpeaker,
} from '../components/Icons';
import { NativeBridge, TranslationResultEvent, DetectionStatusEvent, TapProgressEvent } from '../services/nativeModules';
import { Storage } from '../services/storage';

interface TranslatorScreenProps {
  onBack: () => void;
  isDarkMode?: boolean;
}

export const TranslatorScreen: React.FC<TranslatorScreenProps> = ({
  onBack,
  isDarkMode = false,
}) => {
  const [mode, setMode] = useState<'tap' | 'live'>('tap');
  const [vocabulary, setVocabulary] = useState<'words' | 'letters'>('words');
  const [showFilipino, setShowFilipino] = useState(true);
  const [isFrontCamera, setIsFrontCamera] = useState(false);

  // Status & Recognition
  const [statusText, setStatusText] = useState('Starting…');
  const [statusColor, setStatusColor] = useState('#2E7D32');
  const [handsWarning, setHandsWarning] = useState(false);

  // Live Mode Stats
  const [liveFrames, setLiveFrames] = useState(0);
  const [bufferPercent, setBufferPercent] = useState(0);
  const [velocity, setVelocity] = useState('—');
  const [streak, setStreak] = useState(0);

  // Tap Mode State
  const [isRecording, setIsRecording] = useState(false);
  const [recordingSeconds, setRecordingSeconds] = useState(0);
  const ringAnim = useRef(new Animated.Value(0)).current;

  // Translation Result
  const [resultWord, setResultWord] = useState<string | null>(null);
  const [resultFilipino, setResultFilipino] = useState<string | null>(null);
  const [confidence, setConfidence] = useState<number | null>(null);

  // SOS button hold timer
  const sosTimerRef = useRef<NodeJS.Timeout | null>(null);
  const [sosHolding, setSosHolding] = useState(false);

  useEffect(() => {
    const settings = Storage.getSettings();
    setMode(settings.translationMode || 'tap');
    setShowFilipino(settings.showFilipino);
    setIsFrontCamera(settings.isFrontCamera);

    NativeBridge.startRecognition(settings.translationMode || 'tap', vocabulary);

    const subResult = NativeBridge.onTranslationResult((event: TranslationResultEvent) => {
      setResultWord(event.word);
      setResultFilipino(event.filipino || null);
      setConfidence(event.confidence);

      // Save to local device history
      Storage.addHistoryEntry(event.word, event.confidence, event.gestureType);

      // Speak automatically if volume is enabled
      const curSettings = Storage.getSettings();
      NativeBridge.speak(event.word, curSettings.volume, curSettings.voiceType);
    });

    const subStatus = NativeBridge.onDetectionStatus((event: DetectionStatusEvent) => {
      setStatusText(event.status);
      setLiveFrames(event.frames);
      setBufferPercent(event.bufferPercent);
      setVelocity(event.velocity);
      setStreak(event.streak);
      setHandsWarning(event.handsWarning);
    });

    const subProgress = NativeBridge.onTapProgress((event: TapProgressEvent) => {
      setIsRecording(event.isRecording);
      setRecordingSeconds(event.elapsedSeconds);
      ringAnim.setValue(event.progressPercent / 100);
    });

    return () => {
      NativeBridge.stopRecognition();
      subResult?.remove();
      subStatus?.remove();
      subProgress?.remove();
      if (sosTimerRef.current) clearTimeout(sosTimerRef.current);
    };
  }, [vocabulary]);

  const toggleMode = (newMode: 'tap' | 'live') => {
    setMode(newMode);
    Storage.updateSettings({ translationMode: newMode });
    NativeBridge.startRecognition(newMode, vocabulary);
  };

  const handleFlipCamera = () => {
    const nextFacing = !isFrontCamera;
    setIsFrontCamera(nextFacing);
    Storage.updateSettings({ isFrontCamera: nextFacing });
    NativeBridge.flipCamera();
  };

  const handleTapRecord = () => {
    if (isRecording) {
      NativeBridge.stopTapRecording();
      setIsRecording(false);
    } else {
      setResultWord(null);
      NativeBridge.startTapRecording();
      setIsRecording(true);
    }
  };

  const speakResult = () => {
    if (resultWord) {
      const curSettings = Storage.getSettings();
      NativeBridge.speak(resultWord, curSettings.volume, curSettings.voiceType);
    }
  };

  // SOS Press Handlers
  const handleSosPressIn = () => {
    setSosHolding(true);
    sosTimerRef.current = setTimeout(() => {
      NativeBridge.triggerSOS();
      setSosHolding(false);
    }, 2000);
  };

  const handleSosPressOut = () => {
    setSosHolding(false);
    if (sosTimerRef.current) {
      clearTimeout(sosTimerRef.current);
      sosTimerRef.current = null;
    }
  };

  return (
    <View className="flex-1 bg-black">
      {/* Native Camera Feed */}
      <CameraView isFrontCamera={isFrontCamera} />

      <SafeAreaView className="flex-1 justify-between">
        {/* Top Floating Controls */}
        <View className="px-4 pt-2">
          <View className="flex-row items-center justify-between">
            {/* Back Button */}
            <TouchableOpacity
              onPress={onBack}
              className={`w-11 h-11 rounded-full items-center justify-center shadow-md ${
                isDarkMode ? 'bg-[#111B3E]' : 'bg-white'
              }`}
              activeOpacity={0.8}
            >
              <IconBack size={20} color={isDarkMode ? '#C9D8FA' : '#13306B'} />
            </TouchableOpacity>

            {/* Mode Toggle Pill */}
            <View
              className={`flex-row items-center p-1 rounded-full shadow-md ${
                isDarkMode ? 'bg-[#111B3E]' : 'bg-white'
              }`}
            >
              <TouchableOpacity
                onPress={() => toggleMode('tap')}
                className={`px-4 py-1.5 rounded-full ${
                  mode === 'tap'
                    ? isDarkMode ? 'bg-[#C9D8FA]' : 'bg-[#13306B]'
                    : 'bg-transparent'
                }`}
                activeOpacity={0.8}
              >
                <Text
                  className={`text-xs font-bold ${
                    mode === 'tap'
                      ? isDarkMode ? 'text-[#13306B]' : 'text-white'
                      : isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
                  }`}
                >
                  Tap
                </Text>
              </TouchableOpacity>

              <TouchableOpacity
                onPress={() => toggleMode('live')}
                className={`px-4 py-1.5 rounded-full ${
                  mode === 'live'
                    ? isDarkMode ? 'bg-[#C9D8FA]' : 'bg-[#13306B]'
                    : 'bg-transparent'
                }`}
                activeOpacity={0.8}
              >
                <Text
                  className={`text-xs font-bold ${
                    mode === 'live'
                      ? isDarkMode ? 'text-[#13306B]' : 'text-white'
                      : isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
                  }`}
                >
                  Live
                </Text>
              </TouchableOpacity>
            </View>

            {/* Flip Camera Button */}
            <TouchableOpacity
              onPress={handleFlipCamera}
              className={`w-11 h-11 rounded-full items-center justify-center shadow-md ${
                isDarkMode ? 'bg-[#111B3E]' : 'bg-white'
              }`}
              activeOpacity={0.8}
            >
              <IconCameraFlip
                size={22}
                color={isDarkMode ? '#C9D8FA' : '#13306B'}
              />
            </TouchableOpacity>
          </View>

          {/* Floating Result Card */}
          {resultWord && (
            <TouchableOpacity
              onPress={speakResult}
              activeOpacity={0.9}
              className={`mt-4 rounded-2xl p-3 flex-row items-center border shadow-lg ${
                isDarkMode
                  ? 'bg-[#111B3E] border-[#233A73]'
                  : 'bg-white border-[#E2E8F4]'
              }`}
            >
              <View
                className={`w-10 h-10 rounded-full items-center justify-center mr-3 ${
                  isDarkMode ? 'bg-[#172350]' : 'bg-[#EEF3FC]'
                }`}
              >
                <IconChat
                  size={20}
                  color={isDarkMode ? '#B8CCF8' : '#13306B'}
                />
              </View>

              <View className="flex-1">
                <Text
                  className={`text-base font-extrabold uppercase ${
                    isDarkMode ? 'text-[#C9D8FA]' : 'text-[#13306B]'
                  }`}
                  numberOfLines={1}
                >
                  {resultWord}
                </Text>
                {showFilipino && resultFilipino ? (
                  <Text
                    className={`text-xs mt-0.5 ${
                      isDarkMode ? 'text-[#A9B6D6]' : 'text-[#626B7E]'
                    }`}
                    numberOfLines={1}
                  >
                    {resultFilipino}
                  </Text>
                ) : null}
              </View>

              <View className="p-2">
                <IconSpeaker
                  size={20}
                  color={isDarkMode ? '#C9D8FA' : '#13306B'}
                />
              </View>
            </TouchableOpacity>
          )}

          {/* Multiple Hands Warning Pill */}
          {handsWarning && (
            <View className="self-center mt-3 bg-[#E53935] px-4 py-1 rounded-full shadow-md">
              <Text className="text-white text-xs font-bold">
                More than 2 hands detected
              </Text>
            </View>
          )}
        </View>

        {/* Center / Lower Stage Controls */}
        <View className="px-5 mb-2">
          {mode === 'tap' ? (
            /* Tap Mode: Prompt + Big Round Record Button */
            <View className="items-center mb-4">
              {isRecording && (
                <View className="bg-black/60 px-4 py-1 rounded-full mb-3">
                  <Text className="text-white text-xs font-semibold">
                    Recording… {recordingSeconds.toFixed(1)}s
                  </Text>
                </View>
              )}

              <TouchableOpacity
                onPress={handleTapRecord}
                activeOpacity={0.85}
                className={`w-20 h-20 rounded-full items-center justify-center border-4 border-white ${
                  isRecording ? 'bg-[#E53935]' : 'bg-[#13306B]'
                }`}
                style={{
                  elevation: 8,
                  shadowColor: '#000',
                  shadowOffset: { width: 0, height: 4 },
                  shadowOpacity: 0.3,
                  shadowRadius: 6,
                }}
              >
                <Text className="text-white text-xs font-bold text-center px-2">
                  {isRecording ? 'Stop' : 'Tap to sign'}
                </Text>
              </TouchableOpacity>
            </View>
          ) : (
            /* Live Mode: Readout and Buffer Progress Bar */
            <View className="mb-4 bg-black/40 p-3 rounded-2xl">
              <View className="flex-row items-center justify-between mb-1.5">
                <Text className="text-white text-xs font-bold">Detecting</Text>
                <Text className="text-white text-xs font-semibold">
                  {bufferPercent}%
                </Text>
              </View>
              <View className="w-full h-1.5 bg-white/20 rounded-full overflow-hidden">
                <View
                  className="h-full bg-[#13306B] rounded-full"
                  style={{ width: `${bufferPercent}%` }}
                />
              </View>
              <View className="flex-row items-center justify-between mt-1.5">
                <Text className="text-white/80 text-[11px]">
                  Frames: {liveFrames}
                </Text>
                <Text className="text-white/80 text-[11px]">
                  Streak: {streak}
                </Text>
                <Text className="text-white/80 text-[11px]">
                  Velocity: {velocity}
                </Text>
              </View>
            </View>
          )}
        </View>

        {/* Bottom Sheet Controls */}
        <View
          className={`rounded-t-3xl pt-3 pb-6 px-5 border-t shadow-2xl ${
            isDarkMode
              ? 'bg-[#111B3E] border-[#233A73]'
              : 'bg-white border-[#E2E8F4]'
          }`}
          style={{
            elevation: 12,
            shadowColor: '#000',
            shadowOffset: { width: 0, height: -4 },
            shadowOpacity: 0.15,
            shadowRadius: 8,
          }}
        >
          {/* Pill Drag Handle */}
          <View className="w-10 h-1 bg-gray-300 dark:bg-gray-700 rounded-full self-center mb-3" />

          {/* Row 1: Status Dot & Label + SOS Emergency */}
          <View className="flex-row items-center justify-between mb-3">
            <View className="flex-row items-center flex-1 mr-3">
              <View
                className="w-2.5 h-2.5 rounded-full mr-2"
                style={{ backgroundColor: statusColor }}
              />
              <Text
                className={`text-sm font-bold flex-1 ${
                  isDarkMode ? 'text-[#EEF3FC]' : 'text-[#0F1B33]'
                }`}
                numberOfLines={1}
              >
                {statusText}
              </Text>
            </View>

            <TouchableOpacity
              onPressIn={handleSosPressIn}
              onPressOut={handleSosPressOut}
              className={`px-4 py-1.5 rounded-full ${
                sosHolding ? 'bg-[#B71C1C]' : 'bg-[#E53935]'
              }`}
              activeOpacity={0.8}
            >
              <Text className="text-white text-xs font-bold">
                {sosHolding ? 'Holding SOS…' : 'Hold for SOS'}
              </Text>
            </TouchableOpacity>
          </View>

          {/* Row 2: Words / Letters Chips + Filipino Toggle */}
          <View className="flex-row items-center justify-between">
            <View className="flex-row items-center space-x-2">
              <TouchableOpacity
                onPress={() => setVocabulary('words')}
                className={`px-3 py-1.5 rounded-full mr-2 ${
                  vocabulary === 'words'
                    ? isDarkMode ? 'bg-[#C9D8FA]' : 'bg-[#13306B]'
                    : isDarkMode ? 'bg-[#172350]' : 'bg-[#EEF3FC]'
                }`}
              >
                <Text
                  className={`text-xs font-bold ${
                    vocabulary === 'words'
                      ? isDarkMode ? 'text-[#13306B]' : 'text-white'
                      : isDarkMode ? 'text-[#A9B6D6]' : 'text-[#13306B]'
                  }`}
                >
                  Words
                </Text>
              </TouchableOpacity>

              <TouchableOpacity
                onPress={() => setVocabulary('letters')}
                className={`px-3 py-1.5 rounded-full ${
                  vocabulary === 'letters'
                    ? isDarkMode ? 'bg-[#C9D8FA]' : 'bg-[#13306B]'
                    : isDarkMode ? 'bg-[#172350]' : 'bg-[#EEF3FC]'
                }`}
              >
                <Text
                  className={`text-xs font-bold ${
                    vocabulary === 'letters'
                      ? isDarkMode ? 'text-[#13306B]' : 'text-white'
                      : isDarkMode ? 'text-[#A9B6D6]' : 'text-[#13306B]'
                  }`}
                >
                  Letters
                </Text>
              </TouchableOpacity>
            </View>

            <TouchableOpacity
              onPress={() => {
                const nextVal = !showFilipino;
                setShowFilipino(nextVal);
                Storage.updateSettings({ showFilipino: nextVal });
              }}
              className={`px-3 py-1.5 rounded-full ${
                showFilipino
                  ? isDarkMode ? 'bg-[#C9D8FA]' : 'bg-[#13306B]'
                  : isDarkMode ? 'bg-[#172350]' : 'bg-[#EEF3FC]'
              }`}
            >
              <Text
                className={`text-xs font-bold ${
                  showFilipino
                    ? isDarkMode ? 'text-[#13306B]' : 'text-white'
                    : isDarkMode ? 'text-[#A9B6D6]' : 'text-[#13306B]'
                }`}
              >
                Filipino
              </Text>
            </TouchableOpacity>
          </View>
        </View>
      </SafeAreaView>
    </View>
  );
};
