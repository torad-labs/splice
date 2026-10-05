// Hash addresses used by the shared fix actions, normalized for the router.
export const routeOf = (href: string): string => (href.startsWith('#') ? href.slice(1) : href);
