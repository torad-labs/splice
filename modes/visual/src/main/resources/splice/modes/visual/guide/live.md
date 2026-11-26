## Module: live

A live card keeps itself up to date with no turn of yours: a training run's loss, a build's progress, a queue's depth.
The card still reaches no network. It asks the app that frames it to read a feed, a small named JSON value this mode
holds, and something else writes the feed.

- **Draw the first state from data inside the card.** Some apps pass a card's requests on and some do not, and a card
  that waits for its feed stays empty where they do not. Put the latest values in the code and draw them; the feed
  only updates them.
- **Write the feed with `feed`.** Give `name` (1 to 40 letters, digits, `-` or `_`) and `data` (any JSON value except
  `null`, at most 64 KiB). It replaces the value and stamps when it was written. Without `data`, `feed` reads it. Send
  only what the card draws, such as the last 200 points and not the whole log. At most 64 feeds are kept, and a new one
  past that drops the one written longest ago. Feeds live in the daemon's memory: a restart empties them.
- **Keep it fresh without a turn: a script.** Where you have a shell, start a loop that writes the feed itself, and
  leave it running. `splice feed <name>` reads the value from standard input and needs no key:

  ```sh
  while sleep 5; do
    tail -n 1 train.log | awk '{printf "{\"step\":%s,\"loss\":%s}", $2, $4}' | splice feed training
  done
  ```

  Each call starts a program of its own, about half a second of work, so write every few seconds and not faster.
  Tell the person what the loop is, where it runs and how to stop it.
- **Watch it in the card.** The view gives the card two functions:
  - `watchFeed(name, onData, everyMs)` asks for the feed every `everyMs` (default 5000, never less than 1000) and calls
    `onData(data, updated)` when the value changes: `data` is the parsed value and `updated` is a `Date`, when it was
    written. It returns a function that stops it, and it stops by itself when the frame is torn down.
  - `readFeed(name)` asks once and gives a promise of `{ data, updated }`, or `null`.

  ```js
  watchFeed("training", (data, updated) => {
    loss.textContent = data.loss.toFixed(3);
    age.dataset.since = updated.getTime();
  }, 5000);
  ```
- **Both give nothing, and never throw, where they cannot.** An app that does not pass requests on, or a feed nobody
  has written yet, means `onData` is not called and `readFeed` gives `null`: the card keeps what it drew. So draw its
  first state from the card's own data, check `data` before reading into it, and never let a card depend on an
  update arriving.
- **Show how old the data is.** Say "updated 12 s ago" from `updated`, and move it on a timer of your own, so a
  reader can tell fresh data from a script that has stopped.
- **The reply stands without it.** Say in words what the card shows, that it updates itself, and what writes its feed.
