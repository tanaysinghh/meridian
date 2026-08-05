import React from 'react';

// Catches render-time errors so a component crash doesn't blank the whole app.
export default class ErrorBoundary extends React.Component {
  state = { err: null };
  static getDerivedStateFromError(err) { return { err }; }
  componentDidCatch(err, info) {
    console.error('[boundary]', err, info?.componentStack);
  }
  render() {
    if (this.state.err) {
      return (
        <div className="p-8">
          <div className="hairline bg-panel p-6 max-w-2xl">
            <div className="serif text-2xl text-ink">Something crashed.</div>
            <div className="mono text-sm text-tier-critical mt-3 whitespace-pre-wrap">
              {String(this.state.err?.message || this.state.err)}
            </div>
            <button className="mt-6 hairline px-3 py-1.5 text-xs bg-panel2 text-ink"
              onClick={() => this.setState({ err: null })}>Try again</button>
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}
