use external_fixture::Remote as Alias;

mod first {
    pub struct Worker;
    impl Worker {
        pub fn run() -> u32 { 1 }
    }
}

mod second {
    pub struct Worker;
    impl Worker {
        pub fn run() -> u32 { 2 }
    }
}

pub fn outer() -> u32 {
    fn nested() -> u32 { first::Worker::run() }
    nested()
}

pub fn other() -> u32 {
    fn nested() -> u32 { second::Worker::run() }
    nested()
}

pub fn remote(value: &Alias) -> u32 {
    value.read()
}

pub trait Pick { fn pick(&self) -> u32; }

pub fn dynamic(value: &dyn Pick) -> u32 { value.pick() }

pub fn generic<T: Pick>(value: &T) -> u32 { value.pick() }

pub fn unicode(value: &Alias) -> u32 {
    let _marker = "😀é"; external_fixture::value() + value.read()
}
