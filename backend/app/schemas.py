from datetime import date
from typing import Literal
from pydantic import BaseModel, ConfigDict, Field, field_validator

Goal = Literal["weight_loss", "fat_loss", "maintenance", "healthy_habits"]

class ProfileIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    height_cm: float = Field(gt=80, le=250)
    current_weight: float = Field(gt=20, le=500)
    target_weight: float = Field(gt=20, le=500)
    start_date: date
    goal: Goal
    target_date: date | None = None
    weekly_plan: dict = Field(default_factory=dict)
    reminder_settings: dict = Field(default_factory=dict)

    @field_validator("weekly_plan")
    @classmethod
    def validate_weekly_plan(cls, value):
        """限制每周计划的条目数和文本长度。"""
        if len(value) > 7 or any(len(str(k)) > 12 or len(str(v)) > 40 for k, v in value.items()):
            raise ValueError("每周安排最多 7 项，每项文字不超过 40 个字符")
        return value

class RecordIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    record_date: date
    weight: float | None = Field(default=None, gt=20, le=500)
    waist_cm: float | None = Field(default=None, gt=20, le=300)
    diet: str = Field(default="", max_length=2000)
    water_ml: int | None = Field(default=None, ge=0, le=10000)
    sleep_hours: float | None = Field(default=None, ge=0, le=24)
    habits: list[str] = Field(default_factory=list, max_length=30)
    note: str = Field(default="", max_length=2000)

    @field_validator("habits")
    @classmethod
    def validate_habits(cls, value):
        """限制习惯标签长度并移除重复值。"""
        if any(len(item) > 50 for item in value):
            raise ValueError("习惯标签不能超过 50 个字符")
        return list(dict.fromkeys(value))

class ExerciseIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    exercise_date: date
    kind: str = Field(min_length=1, max_length=40)
    duration_minutes: int = Field(ge=1, le=1440)
    note: str = Field(default="", max_length=1000)

class WechatLoginIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    code: str = Field(min_length=1, max_length=256)
