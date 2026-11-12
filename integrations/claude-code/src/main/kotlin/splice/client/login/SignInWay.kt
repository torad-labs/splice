// How a head's provider sign-in is offered inside Claude Code, which decides what its /login says and does. It was a
// Boolean (browser or not) until LAN share added a third way, a command whose logins live on another machine.
package splice.client.login

public sealed class SignInWay {
    /** An OAuth head: /login opens the provider's sign-in in a browser. */
    public data object Browser : SignInWay()

    /** An api-key head: /login points at the masked prompt `<command> login` gives in a terminal. */
    public data object Terminal : SignInWay()

    /** LAN share: a command another machine serves, whose logins live on [machine]. /login names that machine and
     *  where to sign in there, and starts nothing here. */
    public data class Elsewhere(val machine: String) : SignInWay()
}
