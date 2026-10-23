"""
Unit tests for facial_expression_detector.py
"""

import os
import sys

import numpy as np
import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))

from facial_expression_detector import (
    COLOR_GRAY,
    COLOR_DARK_GRAY,
    FacialExpressionDetector,
    draw_face_bounding_box,
    draw_facial_landmarks,
    draw_expression_overlay,
    resolve_model_path,
)


def test_resolve_model_path():
    """Verify that model path resolution locates the task bundle in the workspace."""
    path = resolve_model_path()
    assert path is not None
    assert path.endswith("face_landmarker.task")


def test_tight_gray_bounding_box():
    """Verify that draw_face_bounding_box renders a tight gray bounding box with zero padding."""
    h, w = 480, 640
    frame = np.full((h, w, 3), (20, 20, 20), dtype=np.uint8)

    # Face landmarks situated strictly in [x in 200..350, y in 100..280]
    landmarks = [
        (200, 150),
        (350, 200),
        (275, 100),
        (280, 280),
        (250, 180),
    ]

    box = draw_face_bounding_box(frame, landmarks, dashed=True, color=COLOR_GRAY)
    assert box == (200, 100, 350, 280), f"Bounding box should strictly equal (200, 100, 350, 280), got {box}"

    # Verify no green dots (0, 255, 0) are present
    has_green = np.any((frame[:, :, 0] == 0) & (frame[:, :, 1] == 255) & (frame[:, :, 2] == 0))
    assert not has_green, "Green landmark dots must be removed"

    # Verify gray pixels (180, 180, 180) are present along the bounding box
    has_gray = np.any(
        (frame[:, :, 0] == COLOR_GRAY[0]) &
        (frame[:, :, 1] == COLOR_GRAY[1]) &
        (frame[:, :, 2] == COLOR_GRAY[2])
    )
    assert has_gray, "Gray bounding box must be rendered"


def test_draw_facial_landmarks_alias():
    """Verify that draw_facial_landmarks now renders the gray bounding box rather than mesh dots."""
    h, w = 480, 640
    frame = np.full((h, w, 3), (20, 20, 20), dtype=np.uint8)

    landmarks = [(150, 80), (300, 80), (150, 250), (300, 250)]
    draw_facial_landmarks(frame, landmarks)

    # Ensure no green dots exist
    has_green = np.any((frame[:, :, 0] == 0) & (frame[:, :, 1] == 255) & (frame[:, :, 2] == 0))
    assert not has_green, "Full-face landmark mesh and green dots must be disabled"

    # Ensure gray pixels exist
    has_gray = np.any(
        (frame[:, :, 0] == COLOR_GRAY[0]) &
        (frame[:, :, 1] == COLOR_GRAY[1]) &
        (frame[:, :, 2] == COLOR_GRAY[2])
    )
    assert has_gray, "Bounding box gray lines must be present"


def test_expression_overlay_placement():
    """Verify that draw_expression_overlay draws in the lower-middle portion above bottom bar."""
    h, w = 720, 1280
    frame = np.full((h, w, 3), (20, 20, 20), dtype=np.uint8)

    draw_expression_overlay(frame, "Happy", 0.95, fps=30.0)

    # Lower-middle region: roughly y in [0.70*h, 0.85*h] and x near center
    mid_x = w // 2
    lower_mid_y = int(h * 0.77)

    # Sample a window around the badge center
    badge_region = frame[lower_mid_y - 15 : lower_mid_y + 15, mid_x - 40 : mid_x + 40]
    diff = np.abs(badge_region.astype(int) - 20)
    assert np.any(diff > 10), "Badge must be rendered in the lower-middle portion of the frame"
