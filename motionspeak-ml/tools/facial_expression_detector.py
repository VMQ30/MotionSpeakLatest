"""
Real-Time Webcam Facial Expression Recognition & Landmark Tracking
===================================================================

This module performs real-time webcam facial expression recognition and facial
landmark tracking using OpenCV and MediaPipe Tasks Vision (FaceLandmarker).

Key Features:
- Prominent GREEN DOTS (RGB/BGR: 0, 255, 0) on facial landmark keypoints:
    * Eyes: contours, pupils, inner/outer corners, lids
    * Nose: tip, bridge, columella, alar wings
    * Mouth: lips, oral commissures (mouth corners), cupids bow
    * Cheeks: left and right zygomatic regions
    * Jawline: full mandibular arc from ear to chin to ear
- Real-time facial expression classification:
    * Happy, Surprised, Sad, Angry, Fear, Disgust, Neutral
- Overlay presentation:
    * Detected expression badge displayed in the lower-middle portion of the feed
      (centered horizontally near the bottom third, directly above bottom UI controls)
    * Bottom UI controls status bar with shortcut hints
- Support for webcam live feed, single test images, and video files
- Automatic model discovery & fallback download for `face_landmarker.task`
"""

from __future__ import annotations

import argparse
import os
import sys
import time
import urllib.request
from collections import deque
from typing import Dict, List, Optional, Tuple

import cv2
import numpy as np

# ---------------------------------------------------------------------------
# MediaPipe Imports & Compatibility Handling
# ---------------------------------------------------------------------------
try:
    import mediapipe as mp
    from mediapipe.tasks import python as mp_python
    from mediapipe.tasks.python import vision
    HAS_MEDIAPIPE = True
except ImportError:
    HAS_MEDIAPIPE = False

# Model download URL (official Google MediaPipe CDN)
MODEL_URL = (
    "https://storage.googleapis.com/mediapipe-models/face_landmarker/"
    "face_landmarker/float16/1/face_landmarker.task"
)

# Standard candidate locations for face_landmarker.task in this repository
CANDIDATE_MODEL_PATHS = [
    os.getenv("FACE_LANDMARKER_MODEL", ""),
    os.path.join(os.path.dirname(__file__), "face_landmarker.task"),
    os.path.join(os.path.dirname(__file__), "..", "app", "models", "face_landmarker.task"),
    os.path.join(os.path.dirname(__file__), "motionspeak-ml", "app", "models", "face_landmarker.task"),
    os.path.join(os.path.dirname(__file__), "..", "..", "motionspeak-mobile", "app", "src", "main", "assets", "face_landmarker.task"),
    os.path.join(os.path.dirname(__file__), "..", "motionspeak-mobile", "app", "src", "main", "assets", "face_landmarker.task"),
    os.path.join(os.path.dirname(__file__), "motionspeak-mobile", "app", "src", "main", "assets", "face_landmarker.task"),
]

# ---------------------------------------------------------------------------
# Landmark Keypoint Definitions (Indices in MediaPipe 468/478 Face Mesh)
# ---------------------------------------------------------------------------
# Prominent landmark keypoint groups for eyes, nose, mouth, cheeks, and jawline:
KEYPOINTS_EYES = [
    # Left eye contour & corners
    33, 133, 159, 145, 153, 154, 160, 161, 246,
    # Right eye contour & corners
    263, 362, 386, 374, 380, 381, 385, 387, 466,
    # Pupils / Irises (present in 478-landmark model)
    468, 473,
]

KEYPOINTS_NOSE = [
    # Tip, bridge, dorsal crest, nostrils
    1, 2, 4, 5, 19, 94, 168, 195, 197, 6, 48, 278,
]

KEYPOINTS_MOUTH = [
    # Outer lip vermilion border & corners
    61, 291, 0, 17, 78, 308, 13, 14, 82, 87, 312, 317,
    # Upper & lower lip midpoints
    11, 12, 15, 16, 84, 181, 314, 405,
]

KEYPOINTS_CHEEKS = [
    # Left cheek (zygomatic prominence)
    50, 117, 123, 187, 205, 207,
    # Right cheek (zygomatic prominence)
    280, 346, 352, 411, 425, 427,
]

KEYPOINTS_JAWLINE = [
    # Mandibular margin from left ear to chin (152) to right ear
    234, 93, 132, 58, 172, 136, 150, 149, 176, 148,
    152,  # Mentum / Chin apex
    377, 400, 378, 379, 365, 397, 288, 361, 323, 454,
]

# Silhouette contour line pairs for subtle structural connections
SILHOUETTE_CONNECTIONS = [
    # Jawline
    (234, 93), (93, 132), (132, 58), (58, 172), (172, 136), (136, 150),
    (150, 149), (149, 176), (176, 148), (148, 152), (152, 377), (377, 400),
    (400, 378), (378, 379), (379, 365), (365, 397), (397, 288), (288, 361),
    (361, 323), (323, 454),
    # Left eye
    (33, 7), (7, 163), (163, 144), (144, 145), (145, 153), (153, 154),
    (154, 155), (155, 133), (133, 173), (173, 157), (157, 158), (158, 159),
    (159, 160), (160, 161), (161, 246), (246, 33),
    # Right eye
    (263, 249), (249, 390), (390, 373), (373, 374), (374, 380), (380, 381),
    (381, 382), (382, 362), (362, 398), (398, 384), (384, 385), (385, 386),
    (386, 387), (387, 388), (388, 466), (466, 263),
    # Lips outer
    (61, 146), (146, 91), (91, 181), (181, 84), (84, 17), (17, 314),
    (314, 405), (405, 321), (321, 375), (375, 291), (291, 61),
    # Left eyebrow
    (70, 63), (63, 105), (105, 66), (66, 107), (107, 55),
    # Right eyebrow
    (300, 293), (293, 334), (334, 296), (296, 336), (336, 285),
    # Nose bridge
    (168, 6), (6, 197), (197, 195), (195, 5), (5, 4), (4, 1),
]

# Color constants (BGR for OpenCV)
COLOR_GRAY = (180, 180, 180)       # Light Gray: RGB (180, 180, 180) -> BGR (180, 180, 180)
COLOR_DARK_GRAY = (128, 128, 128) # Mid Gray: RGB (128, 128, 128) -> BGR (128, 128, 128)
COLOR_GREEN = (0, 255, 0)         # Green accent
COLOR_WHITE = (255, 255, 255)
COLOR_BADGE_BG = (17, 24, 39)     # Dark slate background (BGR)
COLOR_ACCENT = (180, 180, 180)    # Gray accent border


def resolve_model_path(custom_path: Optional[str] = None) -> str:
    """Find the MediaPipe face_landmarker.task model, or download it if absent."""
    if custom_path and os.path.isfile(custom_path):
        return os.path.abspath(custom_path)

    for candidate in CANDIDATE_MODEL_PATHS:
        if candidate and os.path.isfile(candidate):
            return os.path.abspath(candidate)

    # If not found, download to current directory
    target_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "face_landmarker.task")
    print(f"[*] Downloading MediaPipe face landmarker model bundle to: {target_path}")
    try:
        urllib.request.urlretrieve(MODEL_URL, target_path)
        print("[+] Download complete.")
        return target_path
    except Exception as exc:
        print(f"[!] Warning: automatic model download failed: {exc}", file=sys.stderr)
        return target_path


class FacialExpressionDetector:
    """
    Real-time facial expression and landmark detector using MediaPipe Tasks Vision.
    """

    def __init__(self, model_path: Optional[str] = None):
        if not HAS_MEDIAPIPE:
            raise RuntimeError(
                "MediaPipe is not installed. Please run:\n"
                "    pip install mediapipe opencv-python"
            )

        resolved_path = resolve_model_path(model_path)
        if not os.path.isfile(resolved_path):
            raise FileNotFoundError(
                f"Face landmarker model bundle not found at: {resolved_path}\n"
                f"Download it from: {MODEL_URL}"
            )

        base_options = mp_python.BaseOptions(model_asset_path=resolved_path)
        options = vision.FaceLandmarkerOptions(
            base_options=base_options,
            output_face_blendshapes=True,
            output_facial_transformation_matrixes=False,
            num_faces=1,
            min_face_detection_confidence=0.5,
            min_face_presence_confidence=0.5,
            min_tracking_confidence=0.5,
            running_mode=vision.RunningMode.IMAGE,
        )
        self.landmarker = vision.FaceLandmarker.create_from_options(options)

        # Temporal smoothing for emotion scores across recent frames
        self.score_history: Dict[str, deque] = {
            "Happy": deque(maxlen=5),
            "Surprised": deque(maxlen=5),
            "Sad": deque(maxlen=5),
            "Angry": deque(maxlen=5),
            "Fear": deque(maxlen=5),
            "Disgust": deque(maxlen=5),
            "Neutral": deque(maxlen=5),
        }
        self.last_predicted_label = "Neutral"
        self.last_predicted_confidence = 0.50

    def detect_frame(self, frame_bgr: np.ndarray) -> Tuple[Optional[List[Tuple[int, int]]], str, float]:
        """
        Process a single BGR camera frame.
        Returns:
            (landmark_pixel_points, emotion_label, confidence_score)
        """
        h, w = frame_bgr.shape[:2]
        frame_rgb = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB)
        mp_img = mp.Image(image_format=mp.ImageFormat.SRGB, data=frame_rgb)

        result = self.landmarker.detect(mp_img)
        if not result.face_landmarks:
            return None, "No Face Detected", 0.0

        raw_landmarks = result.face_landmarks[0]
        points: List[Tuple[int, int]] = []
        for lm in raw_landmarks:
            px = int(lm.x * w)
            py = int(lm.y * h)
            points.append((px, py))

        # Classify expression using blendshapes (if available) or landmark geometry
        blendshapes = {}
        if result.face_blendshapes:
            for b in result.face_blendshapes[0]:
                blendshapes[b.category_name] = b.score

        label, confidence = self._classify_expression(blendshapes, points, w, h)
        return points, label, confidence

    def _classify_expression(
        self,
        bs: Dict[str, float],
        pts: List[Tuple[int, int]],
        width: int,
        height: int,
    ) -> Tuple[str, float]:
        """
        Robust expression classifier combining ARKit blendshape scores
        with facial geometry metrics.
        """
        scores: Dict[str, float] = {}

        if bs:
            # 1. Happy: Smile + Cheek Squint
            smile = (bs.get("mouthSmileLeft", 0.0) + bs.get("mouthSmileRight", 0.0)) / 2.0
            cheek = (bs.get("cheekSquintLeft", 0.0) + bs.get("cheekSquintRight", 0.0)) / 2.0
            scores["Happy"] = float(smile * 0.75 + cheek * 0.25)

            # 2. Surprised: Open Jaw + Raised Eyebrows + Wide Eyes
            jaw_open = bs.get("jawOpen", 0.0)
            brow_up = (
                bs.get("browInnerUp", 0.0) * 0.5
                + (bs.get("browOuterUpLeft", 0.0) + bs.get("browOuterUpRight", 0.0)) * 0.25
            )
            eye_wide = (bs.get("eyeWideLeft", 0.0) + bs.get("eyeWideRight", 0.0)) / 2.0
            scores["Surprised"] = float(jaw_open * 0.50 + brow_up * 0.35 + eye_wide * 0.15)

            # 3. Sad: Mouth Frown + Inner Brow Raised + Outer Brow Down
            frown = (bs.get("mouthFrownLeft", 0.0) + bs.get("mouthFrownRight", 0.0)) / 2.0
            inner_brow_up = bs.get("browInnerUp", 0.0)
            scores["Sad"] = float(frown * 0.60 + inner_brow_up * 0.30 - smile * 0.20)

            # 4. Angry: Furrowed / Lowered Brows + Pressed / Puckered Mouth
            brow_down = (bs.get("browDownLeft", 0.0) + bs.get("browDownRight", 0.0)) / 2.0
            mouth_press = (bs.get("mouthPressLeft", 0.0) + bs.get("mouthPressRight", 0.0)) / 2.0
            mouth_pucker = bs.get("mouthPucker", 0.0)
            scores["Angry"] = float(brow_down * 0.65 + (mouth_press + mouth_pucker) * 0.20)

            # 5. Fear: Wide Eyes + Raised Brows + Slight Open Mouth
            scores["Fear"] = float(eye_wide * 0.45 + brow_up * 0.35 + jaw_open * 0.20)

            # 6. Disgust: Nose Wrinkle (Sneer) + Upper Lip Elevation
            sneer = (bs.get("noseSneerLeft", 0.0) + bs.get("noseSneerRight", 0.0)) / 2.0
            lip_up = (bs.get("mouthUpperUpLeft", 0.0) + bs.get("mouthUpperUpRight", 0.0)) / 2.0
            scores["Disgust"] = float(sneer * 0.60 + lip_up * 0.40)

            # 7. Neutral: Inverse of active emotional activation
            max_active = max(scores.values()) if scores else 0.0
            neutral_score = max(0.0, 1.0 - (max_active * 1.8))
            scores["Neutral"] = float(neutral_score)

        else:
            # Fallback Geometric Landmark Calculations
            scores = self._geometric_expression_scores(pts, width, height)

        # Smooth scores temporally
        smoothed_scores = {}
        for emotion, score in scores.items():
            self.score_history[emotion].append(max(0.0, score))
            smoothed_scores[emotion] = sum(self.score_history[emotion]) / len(self.score_history[emotion])

        # Pick highest scoring emotion
        best_emotion, raw_conf = max(smoothed_scores.items(), key=lambda kv: kv[1])
        # If no emotion reaches minimum activation threshold, default to Neutral
        if best_emotion != "Neutral" and raw_conf < 0.28:
            best_emotion = "Neutral"
            confidence = min(0.95, max(0.50, 1.0 - raw_conf))
        else:
            confidence = min(0.99, max(0.40, raw_conf))

        self.last_predicted_label = best_emotion
        self.last_predicted_confidence = confidence
        return best_emotion, confidence

    def _geometric_expression_scores(
        self, pts: List[Tuple[int, int]], w: int, h: int
    ) -> Dict[str, float]:
        """Geometric fallback expression scoring using landmark coordinates."""
        scores: Dict[str, float] = {
            "Happy": 0.0, "Surprised": 0.0, "Sad": 0.0,
            "Angry": 0.0, "Fear": 0.0, "Disgust": 0.0, "Neutral": 0.5,
        }
        if len(pts) < 468:
            return scores

        # Lip corners & lip center
        left_corner = np.array(pts[61], dtype=np.float32)
        right_corner = np.array(pts[291], dtype=np.float32)
        upper_lip = np.array(pts[0], dtype=np.float32)
        lower_lip = np.array(pts[17], dtype=np.float32)
        nose_tip = np.array(pts[1], dtype=np.float32)

        mouth_width = np.linalg.norm(right_corner - left_corner)
        mouth_height = np.linalg.norm(lower_lip - upper_lip)
        mar = mouth_height / (mouth_width + 1e-6)

        # Smile curvature (corners relative to lip center)
        mid_corner_y = (left_corner[1] + right_corner[1]) / 2.0
        lip_center_y = (upper_lip[1] + lower_lip[1]) / 2.0
        smile_curvature = (lip_center_y - mid_corner_y) / (mouth_width + 1e-6)

        if smile_curvature > 0.04:
            scores["Happy"] = min(0.95, float(smile_curvature * 15.0))
        elif mar > 0.45:
            scores["Surprised"] = min(0.95, float(mar * 1.5))
        elif smile_curvature < -0.04:
            scores["Sad"] = min(0.90, float(-smile_curvature * 12.0))
        else:
            scores["Neutral"] = 0.75

        return scores


# ---------------------------------------------------------------------------
# Rendering Helpers (Clean Tight Gray Bounding Box & Lower-Middle UI Overlay)
# ---------------------------------------------------------------------------
def _draw_dashed_rect(
    img: np.ndarray,
    pt1: Tuple[int, int],
    pt2: Tuple[int, int],
    color: Tuple[int, int, int],
    thickness: int = 2,
    dash_length: int = 12,
) -> None:
    """Draw a clean dashed rectangle between pt1 and pt2 on img."""
    x1, y1 = pt1
    x2, y2 = pt2

    # Top and bottom edges
    for x in range(x1, x2, dash_length * 2):
        x_end = min(x + dash_length, x2)
        cv2.line(img, (x, y1), (x_end, y1), color, thickness, cv2.LINE_AA)
        cv2.line(img, (x, y2), (x_end, y2), color, thickness, cv2.LINE_AA)

    # Left and right edges
    for y in range(y1, y2, dash_length * 2):
        y_end = min(y + dash_length, y2)
        cv2.line(img, (x1, y), (x1, y_end), color, thickness, cv2.LINE_AA)
        cv2.line(img, (x2, y), (x2, y_end), color, thickness, cv2.LINE_AA)


def draw_face_bounding_box(
    frame: np.ndarray,
    landmarks: List[Tuple[int, int]],
    dashed: bool = True,
    color: Tuple[int, int, int] = COLOR_GRAY,
) -> Optional[Tuple[int, int, int, int]]:
    """
    Draw a clean, tight rectangular/square bounding box directly around the detected face.
    Tight coordinates based strictly on the face's bounding box (min_x, min_y to max_x, max_y)
    with zero extra padding so the box sits snugly around the user's face contour.
    """
    if not landmarks:
        return None

    xs = [pt[0] for pt in landmarks]
    ys = [pt[1] for pt in landmarks]
    min_x = max(0, min(xs))
    max_x = min(frame.shape[1], max(xs))
    min_y = max(0, min(ys))
    max_y = min(frame.shape[0], max(ys))

    if max_x <= min_x or max_y <= min_y:
        return None

    if dashed:
        _draw_dashed_rect(frame, (min_x, min_y), (max_x, max_y), color, thickness=2, dash_length=12)
    else:
        cv2.rectangle(frame, (min_x, min_y), (max_x, max_y), color, 2, cv2.LINE_AA)

    return (min_x, min_y, max_x, max_y)


def draw_facial_landmarks(
    frame: np.ndarray,
    landmarks: List[Tuple[int, int]],
    draw_mesh_lines: bool = False,
) -> None:
    """
    Compatibility wrapper: renders the clean, tight gray bounding box around the detected face.
    Full-face landmark mesh / green dots are disabled.
    """
    draw_face_bounding_box(frame, landmarks, dashed=True, color=COLOR_GRAY)


def draw_expression_overlay(
    frame: np.ndarray,
    expression: str,
    confidence: float,
    fps: float = 0.0,
) -> None:
    """
    Overlay the detected expression label text (e.g. 'Happy', 'Neutral', 'Surprised', 'Sad')
    on the live camera screen in the lower-middle portion of the camera feed
    (centered horizontally near the bottom third of the frame, directly above bottom UI controls).
    """
    h, w = frame.shape[:2]

    # -----------------------------------------------------------------------
    # 1. Lower-Middle Expression Badge (Centered horizontally in bottom third)
    # -----------------------------------------------------------------------
    if expression:
        if confidence > 0.0:
            badge_text = f"{expression.upper()}  {int(confidence * 100)}%"
        else:
            badge_text = expression.upper()

        font = cv2.FONT_HERSHEY_DUPLEX
        font_scale = max(0.65, min(1.0, w / 900.0))
        thickness = 2

        (text_w, text_h), baseline = cv2.getTextSize(badge_text, font, font_scale, thickness)

        # Lower-middle vertical placement (around 75% down the frame, above bottom controls)
        center_x = w // 2
        center_y = int(h * 0.77)

        pad_x = 24
        pad_y = 14

        box_x1 = max(0, center_x - text_w // 2 - pad_x)
        box_y1 = max(0, center_y - text_h // 2 - pad_y)
        box_x2 = min(w, center_x + text_w // 2 + pad_x)
        box_y2 = min(h, center_y + text_h // 2 + pad_y + baseline)

        # Translucent pill background
        sub_img = frame[box_y1:box_y2, box_x1:box_x2]
        if sub_img.shape[0] > 0 and sub_img.shape[1] > 0:
            bg_rect = np.full(sub_img.shape, COLOR_BADGE_BG, dtype=np.uint8)
            cv2.addWeighted(bg_rect, 0.75, sub_img, 0.25, 0, sub_img)
            frame[box_y1:box_y2, box_x1:box_x2] = sub_img

        # Prominent green border around badge
        cv2.rectangle(frame, (box_x1, box_y1), (box_x2, box_y2), COLOR_ACCENT, 2, cv2.LINE_AA)

        # Green indicator dot inside the badge
        dot_cx = box_x1 + 16
        dot_cy = center_y + 1
        cv2.circle(frame, (dot_cx, dot_cy), 5, COLOR_GREEN, -1, cv2.LINE_AA)

        # Badge text rendered centered in pure bright white
        text_origin_x = center_x - text_w // 2 + 6
        text_origin_y = center_y + text_h // 2 - 2
        cv2.putText(
            frame,
            badge_text,
            (text_origin_x, text_origin_y),
            font,
            font_scale,
            COLOR_WHITE,
            thickness,
            cv2.LINE_AA,
        )

    # -----------------------------------------------------------------------
    # 2. Bottom UI Controls Bar (Directly below the expression badge)
    # -----------------------------------------------------------------------
    controls_h = 36
    controls_y1 = h - controls_h
    controls_bar = frame[controls_y1:h, 0:w]
    if controls_bar.shape[0] > 0 and controls_bar.shape[1] > 0:
        dark_bar = np.zeros(controls_bar.shape, dtype=np.uint8)
        cv2.addWeighted(dark_bar, 0.70, controls_bar, 0.30, 0, controls_bar)
        frame[controls_y1:h, 0:w] = controls_bar

    # Top thin line for controls bar
    cv2.line(frame, (0, controls_y1), (w, controls_y1), (60, 60, 60), 1)

    controls_text = "[Q] Quit   [M] Mirror   [B] Bounding Box On/Off   [S] Snapshot"
    cv2.putText(
        frame,
        controls_text,
        (16, h - 12),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.44,
        (200, 200, 200),
        1,
        cv2.LINE_AA,
    )

    if fps > 0.0:
        fps_text = f"FPS: {fps:.1f}"
        (fps_w, _), _ = cv2.getTextSize(fps_text, cv2.FONT_HERSHEY_SIMPLEX, 0.44, 1)
        cv2.putText(
            frame,
            fps_text,
            (w - fps_w - 16, h - 12),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.44,
            COLOR_GRAY,
            1,
            cv2.LINE_AA,
        )


# ---------------------------------------------------------------------------
# Main Execution Loop (Webcam / Image / Video)
# ---------------------------------------------------------------------------
def run_detector(
    camera_index: int = 0,
    model_path: Optional[str] = None,
    image_path: Optional[str] = None,
    video_path: Optional[str] = None,
    output_path: Optional[str] = None,
    width: int = 1280,
    height: int = 720,
    mirror: bool = True,
    no_gui: bool = False,
) -> None:
    """Run real-time facial expression and bounding box detection."""
    print("=" * 65)
    print("  MotionSpeak: Real-Time Facial Expression & Detection")
    print("=" * 65)

    detector = FacialExpressionDetector(model_path=model_path)
    show_bounding_box = True
    mirror_feed = mirror

    # Single Image Mode
    if image_path:
        print(f"[*] Processing single image: {image_path}")
        frame = cv2.imread(image_path)
        if frame is None:
            raise FileNotFoundError(f"Could not load image file: {image_path}")

        landmarks, expression, confidence = detector.detect_frame(frame)
        if landmarks:
            draw_face_bounding_box(frame, landmarks, dashed=True, color=COLOR_GRAY)
        draw_expression_overlay(frame, expression, confidence)

        print(f"[+] Result: Expression = '{expression}' (Confidence: {confidence:.1%})")
        if output_path:
            cv2.imwrite(output_path, frame)
            print(f"[+] Output image saved to: {output_path}")

        if not no_gui:
            cv2.imshow("Facial Expression Recognition (Press any key to close)", frame)
            cv2.waitKey(0)
            cv2.destroyAllWindows()
        return

    # Video or Live Webcam Mode
    if video_path:
        print(f"[*] Reading from video file: {video_path}")
        cap = cv2.VideoCapture(video_path)
        mirror_feed = False
    else:
        print(f"[*] Initializing camera index {camera_index}...")
        cap = cv2.VideoCapture(camera_index, cv2.CAP_DSHOW if sys.platform == "win32" else cv2.CAP_ANY)
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, width)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, height)

    if not cap.isOpened():
        raise RuntimeError(
            f"Failed to open video source (Camera {camera_index} / Video: {video_path}).\n"
            "Ensure the webcam is connected and not currently used by another application."
        )

    writer = None
    if output_path:
        fourcc = cv2.VideoWriter_fourcc(*"mp4v")
        writer = cv2.VideoWriter(output_path, fourcc, 30.0, (width, height))
        print(f"[*] Recording output to: {output_path}")

    print("[+] Detector running. Press 'Q' or ESC in window to exit.")
    fps = 0.0
    frame_count = 0
    t_start = time.time()

    try:
        while True:
            ret, frame = cap.read()
            if not ret or frame is None:
                if video_path:
                    print("[*] Reached end of video file.")
                    break
                print("[!] Warning: empty frame received from camera.")
                time.sleep(0.05)
                continue

            frame_count += 1
            if mirror_feed:
                frame = cv2.flip(frame, 1)

            # Detect landmarks and classify expression
            landmarks, expression, confidence = detector.detect_frame(frame)

            # Draw clean tight gray bounding box directly around the detected face
            if landmarks and show_bounding_box:
                draw_face_bounding_box(frame, landmarks, dashed=True, color=COLOR_GRAY)

            # Calculate live FPS
            elapsed = time.time() - t_start
            if elapsed >= 0.5:
                fps = frame_count / elapsed
                frame_count = 0
                t_start = time.time()

            # Overlay expression badge in lower-middle portion (above bottom controls)
            draw_expression_overlay(frame, expression, confidence, fps=fps)

            if writer:
                writer.write(frame)

            if not no_gui:
                cv2.imshow("MotionSpeak - Facial Expression Recognition", frame)
                key = cv2.waitKey(1) & 0xFF
                if key in (ord("q"), ord("Q"), 27):  # 'q' or ESC
                    break
                elif key in (ord("m"), ord("M")):
                    mirror_feed = not mirror_feed
                elif key in (ord("b"), ord("B"), ord("l"), ord("L")):
                    show_bounding_box = not show_bounding_box
                elif key in (ord("s"), ord("S")):
                    snap_name = f"snapshot_emotion_{int(time.time())}.jpg"
                    cv2.imwrite(snap_name, frame)
                    print(f"[+] Saved snapshot to {snap_name}")

    finally:
        cap.release()
        if writer:
            writer.release()
        if not no_gui:
            cv2.destroyAllWindows()
        print("[+] Session closed cleanly.")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Real-Time Webcam Facial Expression Recognition & Landmark Tracking (OpenCV + MediaPipe)"
    )
    parser.add_argument("--camera", type=int, default=0, help="Webcam device index (default: 0)")
    parser.add_argument("--model", type=str, default=None, help="Path to face_landmarker.task model")
    parser.add_argument("--image", type=str, default=None, help="Path to input image for single-frame testing")
    parser.add_argument("--video", type=str, default=None, help="Path to input video file")
    parser.add_argument("--output", type=str, default=None, help="Path to save output image or video")
    parser.add_argument("--width", type=int, default=1280, help="Webcam capture width (default: 1280)")
    parser.add_argument("--height", type=int, default=720, help="Webcam capture height (default: 720)")
    parser.add_argument("--no-mirror", action="store_true", help="Disable horizontal flip of webcam stream")
    parser.add_argument("--no-gui", action="store_true", help="Run without opening display window")
    return parser.parse_args()


if __name__ == "__main__":
    args = parse_args()
    run_detector(
        camera_index=args.camera,
        model_path=args.model,
        image_path=args.image,
        video_path=args.video,
        output_path=args.output,
        width=args.width,
        height=args.height,
        mirror=not args.no_mirror,
        no_gui=args.no_gui,
    )

