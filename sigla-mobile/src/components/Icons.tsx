import React from 'react';
import { View, Text, StyleSheet } from 'react-native';

export interface IconProps {
  size?: number;
  color?: string;
  className?: string;
}

export const IconHome: React.FC<IconProps> = ({ size = 22, color = '#13306B' }) => (
  <View style={[styles.center, { width: size, height: size }]}>
    <Text style={{ fontSize: size * 0.9, color, textAlign: 'center', lineHeight: size }}>
      ⌂
    </Text>
  </View>
);

export const IconBook: React.FC<IconProps> = ({ size = 22, color = '#13306B' }) => (
  <View style={[styles.center, { width: size, height: size }]}>
    <Text style={{ fontSize: size * 0.85, color, textAlign: 'center', lineHeight: size }}>
      📖
    </Text>
  </View>
);

export const IconHand: React.FC<IconProps> = ({ size = 26, color = '#FFFFFF' }) => (
  <View style={[styles.center, { width: size, height: size }]}>
    <Text style={{ fontSize: size * 0.85, color, textAlign: 'center', lineHeight: size }}>
      ✋
    </Text>
  </View>
);

export const IconHistory: React.FC<IconProps> = ({ size = 22, color = '#13306B' }) => (
  <View style={[styles.center, { width: size, height: size }]}>
    <Text style={{ fontSize: size * 0.85, color, textAlign: 'center', lineHeight: size }}>
      🕒
    </Text>
  </View>
);

export const IconSettings: React.FC<IconProps> = ({ size = 22, color = '#13306B' }) => (
  <View style={[styles.center, { width: size, height: size }]}>
    <Text style={{ fontSize: size * 0.9, color, textAlign: 'center', lineHeight: size }}>
      ⚙
    </Text>
  </View>
);

export const IconSearch: React.FC<IconProps> = ({ size = 18, color = '#626B7E' }) => (
  <View style={[styles.center, { width: size, height: size }]}>
    <Text style={{ fontSize: size * 0.9, color, textAlign: 'center', lineHeight: size }}>
      🔍
    </Text>
  </View>
);

export const IconBack: React.FC<IconProps> = ({ size = 22, color = '#13306B' }) => (
  <View style={[styles.center, { width: size, height: size }]}>
    <Text style={{ fontSize: size * 1.1, color, fontWeight: '700', textAlign: 'center', lineHeight: size }}>
      ‹
    </Text>
  </View>
);

export const IconCameraFlip: React.FC<IconProps> = ({ size = 22, color = '#13306B' }) => (
  <View style={[styles.center, { width: size, height: size }]}>
    <Text style={{ fontSize: size * 0.85, color, textAlign: 'center', lineHeight: size }}>
      🔄
    </Text>
  </View>
);

export const IconTag: React.FC<IconProps> = ({ size = 20, color = '#13306B' }) => (
  <View style={[styles.center, { width: size, height: size }]}>
    <Text style={{ fontSize: size * 0.85, color, textAlign: 'center', lineHeight: size }}>
      🏷
    </Text>
  </View>
);

export const IconChat: React.FC<IconProps> = ({ size = 18, color = '#13306B' }) => (
  <View style={[styles.center, { width: size, height: size }]}>
    <Text style={{ fontSize: size * 0.85, color, textAlign: 'center', lineHeight: size }}>
      💬
    </Text>
  </View>
);

export const IconSpeaker: React.FC<IconProps> = ({ size = 20, color = '#13306B' }) => (
  <View style={[styles.center, { width: size, height: size }]}>
    <Text style={{ fontSize: size * 0.85, color, textAlign: 'center', lineHeight: size }}>
      🔊
    </Text>
  </View>
);

export const IconHeart: React.FC<IconProps & { filled?: boolean }> = ({
  size = 20,
  color = '#13306B',
  filled = false,
}) => (
  <View style={[styles.center, { width: size, height: size }]}>
    <Text style={{ fontSize: size * 0.9, color, textAlign: 'center', lineHeight: size }}>
      {filled ? '♥' : '♡'}
    </Text>
  </View>
);

export const IconDownload: React.FC<IconProps> = ({ size = 20, color = '#13306B' }) => (
  <View style={[styles.center, { width: size, height: size }]}>
    <Text style={{ fontSize: size * 0.9, color, textAlign: 'center', lineHeight: size }}>
      ⬇
    </Text>
  </View>
);

export const IconTrash: React.FC<IconProps> = ({ size = 18, color = '#E53935' }) => (
  <View style={[styles.center, { width: size, height: size }]}>
    <Text style={{ fontSize: size * 0.85, color, textAlign: 'center', lineHeight: size }}>
      🗑
    </Text>
  </View>
);

export const IconPlay: React.FC<IconProps> = ({ size = 24, color = '#FFFFFF' }) => (
  <View style={[styles.center, { width: size, height: size }]}>
    <Text style={{ fontSize: size * 0.85, color, textAlign: 'center', lineHeight: size }}>
      ▶
    </Text>
  </View>
);

export const IconPause: React.FC<IconProps> = ({ size = 24, color = '#FFFFFF' }) => (
  <View style={[styles.center, { width: size, height: size }]}>
    <Text style={{ fontSize: size * 0.85, color, textAlign: 'center', lineHeight: size }}>
      ⏸
    </Text>
  </View>
);

export const IconReplay: React.FC<IconProps> = ({ size = 20, color = '#13306B' }) => (
  <View style={[styles.center, { width: size, height: size }]}>
    <Text style={{ fontSize: size * 0.85, color, textAlign: 'center', lineHeight: size }}>
      ↺
    </Text>
  </View>
);

export const IconFullscreen: React.FC<IconProps> = ({ size = 18, color = '#13306B' }) => (
  <View style={[styles.center, { width: size, height: size }]}>
    <Text style={{ fontSize: size * 0.85, color, textAlign: 'center', lineHeight: size }}>
      ⛶
    </Text>
  </View>
);

export const IconClose: React.FC<IconProps> = ({ size = 18, color = '#626B7E' }) => (
  <View style={[styles.center, { width: size, height: size }]}>
    <Text style={{ fontSize: size * 0.9, color, fontWeight: '700', textAlign: 'center', lineHeight: size }}>
      ✕
    </Text>
  </View>
);

export const IconCheck: React.FC<IconProps> = ({ size = 18, color = '#2E7D32' }) => (
  <View style={[styles.center, { width: size, height: size }]}>
    <Text style={{ fontSize: size * 0.9, color, fontWeight: '700', textAlign: 'center', lineHeight: size }}>
      ✓
    </Text>
  </View>
);

const styles = StyleSheet.create({
  center: {
    alignItems: 'center',
    justifyContent: 'center',
  },
});
