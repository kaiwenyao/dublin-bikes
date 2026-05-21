import { Link } from 'react-router-dom'
import coverImg from '@/assets/img/cover.png'

export default function Home() {
  return (
    <section className="relative min-h-screen w-full overflow-hidden">
      {/* Background Image */}
      <img
        src={coverImg}
        alt="City by bike"
        className="absolute inset-0 h-full w-full object-cover"
        draggable={false}
      />

      {/* Gradient Overlay */}
      <div
        className="absolute inset-0 bg-linear-to-b from-black/50 via-black/40 to-black/60"
        aria-hidden
      />

      {/* Decorative Blur Elements */}
      <div className="absolute inset-0 overflow-hidden" aria-hidden>
        <div className="absolute top-20 right-10 h-40 w-40 rounded-full bg-primary/30 blur-3xl" />
        <div className="absolute bottom-20 left-10 h-32 w-32 rounded-full bg-accent/30 blur-3xl" />
      </div>

      {/* Content */}
      <div className="relative z-10 flex min-h-screen flex-col items-center justify-center px-6 pt-20 text-center">
        <div className="max-w-3xl">
          {/* Badge */}
          <div className="mb-6 inline-flex items-center gap-2 rounded-full bg-white/10 px-4 py-2 backdrop-blur-sm">
            <span className="h-2 w-2 rounded-full bg-green-400 animate-pulse" />
            <span className="text-sm font-medium text-white/90">
              Available 24/7 in your city
            </span>
          </div>

          {/* Headline */}
          <h1 className="text-4xl font-bold leading-tight text-white drop-shadow-lg sm:text-5xl md:text-6xl lg:text-7xl">
            Rediscover the city
            <span className="mt-2 block bg-linear-to-r from-primary to-secondary bg-clip-text text-transparent">
              by bike
            </span>
          </h1>

          {/* Subheadline */}
          <p className="mx-auto mt-6 max-w-xl text-lg text-white/80 drop-shadow-md sm:text-xl">
            Sustainable, healthy, and fun. Join thousands of riders exploring their city on two wheels.
          </p>

          {/* CTA Buttons */}
          <div className="mt-10 flex flex-col items-center gap-4 sm:flex-row sm:justify-center">
            <Link
              to="/register"
              className="group relative inline-flex items-center gap-2 rounded-xl bg-[#00A8E8] px-8 py-4 text-base font-semibold text-white shadow-xl shadow-[#00A8E8]/30 transition-all duration-300 hover:bg-[#007EA7] hover:shadow-[#007EA7]/50 hover:-translate-y-0.5 cursor-pointer"
            >
              Get Started Free
              <svg
                className="h-5 w-5 transition-transform group-hover:translate-x-1"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <line x1="5" y1="12" x2="19" y2="12" />
                <polyline points="12 5 19 12 12 19" />
              </svg>
            </Link>
            <Link
              to="/login"
              className="inline-flex items-center gap-2 rounded-xl border border-white/30 bg-white/10 px-8 py-4 text-base font-semibold text-white backdrop-blur-sm transition-all duration-300 hover:bg-white/20 hover:border-white/50 cursor-pointer"
            >
              Sign In
            </Link>
          </div>

          {/* Stats */}
          <div className="mt-16 grid grid-cols-3 gap-8">
            <div className="text-center">
              <p className="text-3xl font-bold text-white sm:text-4xl">10K+</p>
              <p className="mt-1 text-sm text-white/60">Active Riders</p>
            </div>
            <div className="text-center">
              <p className="text-3xl font-bold text-white sm:text-4xl">50+</p>
              <p className="mt-1 text-sm text-white/60">Cities</p>
            </div>
            <div className="text-center">
              <p className="text-3xl font-bold text-white sm:text-4xl">1M+</p>
              <p className="mt-1 text-sm text-white/60">Rides Completed</p>
            </div>
          </div>
        </div>

        {/* Scroll Indicator */}
        <div className="absolute bottom-8 left-1/2 -translate-x-1/2 animate-bounce">
          <svg
            className="h-6 w-6 text-white/50"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
          >
            <polyline points="6 9 12 15 18 9" />
          </svg>
        </div>
      </div>
    </section>
  )
}
