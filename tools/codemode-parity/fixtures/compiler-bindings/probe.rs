#![allow(dead_code)]
mod alpha {
    pub struct Worker;
    impl Worker { pub fn run() -> u32 { 11 } }
    pub fn helper() -> u32 { 7 }
}
mod beta {
    pub struct Worker;
    impl Worker { pub fn run() -> u32 { 29 } }
}
pub use alpha::Worker as PublicWorker;
mod facade { pub use super::alpha::*; }
pub fn outer() -> u32 {
    fn nested() -> u32 { alpha::Worker::run() }
    nested() + facade::helper()
}
pub fn other() -> u32 {
    fn nested() -> u32 { beta::Worker::run() }
    nested()
}
pub fn qualified() -> u32 { alpha::Worker::run() + beta::Worker::run() }
pub trait Choose { fn pick(&self) -> u32; }
impl Choose for alpha::Worker { fn pick(&self) -> u32 { 13 } }
pub fn dynamic(value: &dyn Choose) -> u32 { value.pick() }
pub fn unicode() -> u32 { let _text = "💡é"; alpha::helper() }
pub struct Options { pub count: u32 }
pub enum Mode { First, Named { value: u32 } }
pub fn generic<T: Choose>(value: &T) -> u32 { value.pick() }
