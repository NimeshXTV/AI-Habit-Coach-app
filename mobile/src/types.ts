export interface Habit {
  id: number;
  name: string;
  emoji: string;
  time_of_day: string; // 'HH:MM' 24h
  duration_minutes: number;
  total_days: number;
  created_at: string;
  status: 'active' | 'completed' | 'stopped';
}

export interface HabitDay {
  id: number;
  habit_id: number;
  day_number: number;
  status: 'pending' | 'done' | 'missed' | 'snoozed';
  action: string | null;
  feedback_reason: string | null;
  feedback_note: string | null;
  intervention_text: string | null;
  intervention_strategy: string | null;
  snooze_count: number;
  completed_at: string | null;
  created_at: string;
}

export interface CurrentIntervention {
  habit: Habit;
  day_number: number;
  total_days?: number;
  intervention_text?: string;
  strategy?: string;
  generated_by?: 'strands' | 'template';
  suggested_time?: string;
  finished: boolean;
  summary?: string;
  summary_generated_by?: 'strands' | 'template';
}

export interface ActionResult {
  ok: boolean;
  day_number: number;
  response_text?: string;
  response_kind?: string;
  generated_by?: 'strands' | 'template';
  consecutive_missed_days?: number;
}

export type FeedbackReason =
  | 'too_tired'
  | 'no_time'
  | 'forgot'
  | 'something_came_up'
  | 'not_feeling_it'
  | 'other';

export type Gender = 'male' | 'female' | 'other';

export interface UserProfile {
  id: number;
  name: string;
  age: number;
  gender: Gender;
  created_at: string;
  updated_at: string;
}
