pub struct Remote {
    pub count: u32,
}

impl Remote {
    pub fn read(&self) -> u32 {
        self.count
    }
}

pub fn value() -> u32 {
    7
}
