use chrono::{NaiveDate, Datelike, Utc};
use crate::models::{Task, Routine, RoutineLogEntry};
use crate::storage::Storage;

pub struct Planner {
    storage: Storage,
}

impl Planner {
    pub fn new(storage: Storage) -> Self {
        Self { storage }
    }

    pub fn add_task(&self, title: &str, description: Option<&str>, due: Option<NaiveDate>, priority: &str) -> anyhow::Result<i64> {
        let priority = match priority {
            "high" | "h" => "high",
            "low" | "l" => "low",
            _ => "medium",
        };
        let id = self.storage.insert_task(title, description, due, priority)?;
        Ok(id)
    }

    pub fn list_tasks(&self, today_only: bool, all: bool) -> anyhow::Result<Vec<Task>> {
        let tasks = self.storage.get_tasks(today_only, all)?;
        Ok(tasks)
    }

    pub fn complete_task(&self, id: i64) -> anyhow::Result<()> {
        if !self.storage.complete_task(id)? {
            anyhow::bail!("Task {} not found", id);
        }
        Ok(())
    }

    pub fn delete_task(&self, id: i64) -> anyhow::Result<()> {
        if !self.storage.delete_task(id)? {
            anyhow::bail!("Task {} not found", id);
        }
        Ok(())
    }

    pub fn edit_task(&self, id: i64, title: Option<&str>, desc: Option<Option<&str>>, due: Option<Option<NaiveDate>>, priority: Option<&str>) -> anyhow::Result<()> {
        let priority = priority.map(|p| match p {
            "high" | "h" => "high",
            "low" | "l" => "low",
            _ => "medium",
        });
        if !self.storage.update_task(id, title, desc, due, priority)? {
            anyhow::bail!("Task {} not found", id);
        }
        Ok(())
    }

    pub fn week_tasks(&self) -> anyhow::Result<Vec<Task>> {
        let today = Utc::now().date_naive();
        let week_start = today - chrono::Duration::days(today.weekday().num_days_from_monday() as i64);
        let week_end = week_start + chrono::Duration::days(6);
        self.storage.get_tasks_for_week(week_start, week_end).map_err(Into::into)
    }

    pub fn add_routine_step(&self, name: &str, order: i32, minutes: Option<i32>) -> anyhow::Result<i64> {
        Ok(self.storage.insert_routine_step(name, order, minutes)?)
    }

    pub fn remove_routine_step(&self, id: i64) -> anyhow::Result<()> {
        if !self.storage.delete_routine_step(id)? {
            anyhow::bail!("Routine step {} not found", id);
        }
        Ok(())
    }

    pub fn show_routine(&self) -> anyhow::Result<Vec<RoutineLogEntry>> {
        let steps = self.storage.get_routine_steps()?;
        let mut log = Vec::new();
        for step in &steps {
            let entry = self.storage.ensure_routine_log_for_today(step.id)?;
            log.push(entry);
        }
        if log.is_empty() {
            self.storage.seed_default_routines()?;
            return self.show_routine();
        }
        Ok(log)
    }

    pub fn check_routine_step(&self, log_id: i64) -> anyhow::Result<()> {
        if !self.storage.check_routine_step(log_id)? {
            anyhow::bail!("Routine log entry {} not found or already completed", log_id);
        }
        Ok(())
    }

    pub fn get_routine_steps(&self) -> anyhow::Result<Vec<Routine>> {
        Ok(self.storage.get_routine_steps()?)
    }

    pub fn seed_routines(&self) -> anyhow::Result<usize> {
        Ok(self.storage.seed_default_routines()?)
    }

    /// Returns today's pending tasks (for briefing integration)
    pub fn today_tasks(&self) -> anyhow::Result<Vec<Task>> {
        self.storage.get_tasks(true, false).map_err(Into::into)
    }

    /// Returns all pending tasks (for briefing integration)
    pub fn pending_tasks(&self) -> anyhow::Result<Vec<Task>> {
        self.storage.get_tasks(false, false).map_err(Into::into)
    }

    /// Returns today's routine with completion status (for briefing integration)
    pub fn today_routine(&self) -> anyhow::Result<Vec<(Routine, RoutineLogEntry)>> {
        let steps = self.storage.get_routine_steps()?;
        let mut result = Vec::new();
        for step in &steps {
            let entry = self.storage.ensure_routine_log_for_today(step.id)?;
            result.push((step.clone(), entry));
        }
        Ok(result)
    }
}
