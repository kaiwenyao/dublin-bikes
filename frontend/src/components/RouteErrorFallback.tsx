export default function RouteErrorFallback() {
  return (
    <main className="flex min-h-screen items-center justify-center bg-slate-50 px-6 text-slate-900">
      <section className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-8 text-center shadow-sm">
        <h1 className="text-2xl font-semibold">Unable to load this page</h1>
        <p className="mt-3 text-sm leading-6 text-slate-600">
          The app may have been updated while this tab was open. Refresh to load the latest version.
        </p>
        <button
          type="button"
          className="mt-6 rounded-lg bg-slate-900 px-5 py-2.5 text-sm font-medium text-white hover:bg-slate-700"
          onClick={() => window.location.reload()}
        >
          Refresh page
        </button>
      </section>
    </main>
  )
}
