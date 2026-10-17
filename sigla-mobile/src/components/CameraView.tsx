import React from 'react';
import { requireNativeComponent, View, StyleSheet, Platform, Text } from 'react-native';

const NativeCameraView: any = Platform.OS === 'android'
  ? (() => {
      try {
        return requireNativeComponent('SiglaCameraPreview');
      } catch (e) {
        return null;
      }
    })()
  : null;

interface CameraViewProps {
  isFrontCamera?: boolean;
  style?: any;
}

export const CameraView: React.FC<CameraViewProps> = ({ isFrontCamera = false, style }) => {
  if (NativeCameraView) {
    return <NativeCameraView style={[styles.container, style]} isFrontCamera={isFrontCamera} />;
  }

  // Graceful fallback for non-native preview / test environments
  return (
    <View style={[styles.container, styles.fallback, style]}>
      <Text style={styles.fallbackText}>Camera Feed & Landmarking</Text>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: '#000000',
  },
  fallback: {
    alignItems: 'center',
    justifyContent: 'center',
  },
  fallbackText: {
    color: 'rgba(255, 255, 255, 0.4)',
    fontSize: 14,
    fontWeight: '600',
  },
});
