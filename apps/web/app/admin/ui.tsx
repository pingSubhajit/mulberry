import { LoaderCircle, type LucideIcon } from "lucide-react"
import type { ComponentProps, ReactNode } from "react"

import { cn } from "@/lib/utils"

const focusRing = "outline-none focus-visible:ring-2 focus-visible:ring-admin-fg/20"

export const fieldClass = cn(
  "h-9 w-full rounded-ctl border border-admin-line-strong bg-admin-panel px-3 text-sm text-admin-fg",
  "transition-[border-color,box-shadow] duration-150 ease-admin placeholder:text-admin-muted",
  "outline-none focus:border-admin-fg/40 focus:ring-[3px] focus:ring-admin-fg/[0.06] disabled:opacity-60"
)

export const numberFieldClass = cn(
  fieldClass,
  "font-mono tabular-nums [appearance:textfield]",
  "[&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
)

export const textareaClass = cn(fieldClass, "h-auto resize-none py-2 leading-5")

type ButtonVariant = "primary" | "secondary" | "ghost" | "danger"

const buttonVariants: Record<ButtonVariant, string> = {
  primary:
    "bg-admin-primary text-admin-primary-fg hover:bg-admin-primary-hover disabled:bg-admin-fill-2 disabled:text-admin-muted",
  secondary:
    "border border-admin-line-strong bg-admin-panel text-admin-fg hover:bg-admin-fill disabled:opacity-50",
  ghost: "text-admin-fg-2 hover:bg-admin-fill hover:text-admin-fg disabled:opacity-50",
  danger:
    "border border-admin-line-strong bg-admin-panel text-admin-negative hover:bg-admin-fill disabled:opacity-50"
}

export function AdminButton({
  variant = "primary",
  className,
  type = "button",
  ...props
}: ComponentProps<"button"> & { variant?: ButtonVariant }) {
  return (
    <button
      type={type}
      className={cn(
        "inline-flex h-8 shrink-0 items-center justify-center gap-2 rounded-ctl px-3 text-[13px] font-medium",
        "transition-[background-color,color,transform] duration-150 ease-admin active:scale-[0.98] disabled:pointer-events-none",
        "[&_svg]:size-4 [&_svg]:stroke-[1.5]",
        focusRing,
        buttonVariants[variant],
        className
      )}
      {...props}
    />
  )
}

export function IconButton({
  tone = "neutral",
  className,
  type = "button",
  ...props
}: ComponentProps<"button"> & { tone?: "neutral" | "danger" }) {
  return (
    <button
      type={type}
      className={cn(
        "inline-flex size-8 shrink-0 items-center justify-center rounded-ctl text-admin-muted",
        "transition-[background-color,color,transform] duration-150 ease-admin hover:bg-admin-fill active:scale-[0.97] disabled:opacity-40",
        "[&_svg]:size-4 [&_svg]:stroke-[1.5]",
        tone === "danger" ? "hover:text-admin-negative" : "hover:text-admin-fg",
        focusRing,
        className
      )}
      {...props}
    />
  )
}

/** The 48px bar at the top of the working panel: what this page is, and its one action. */
export function TopBar({
  icon: Icon,
  title,
  meta,
  children
}: Readonly<{ icon: LucideIcon; title: string; meta?: ReactNode; children?: ReactNode }>) {
  return (
    <header className="flex h-12 shrink-0 items-center gap-3 border-b border-admin-line px-4">
      <Icon className="size-4 shrink-0 text-admin-muted" strokeWidth={1.5} />
      <h1 className="text-sm font-medium text-admin-fg">{title}</h1>
      {meta ? <span className="hidden font-mono text-xs tabular-nums text-admin-muted sm:inline">{meta}</span> : null}
      {children ? <div className="ml-auto flex items-center gap-2">{children}</div> : null}
    </header>
  )
}

export function SectionLabel({ children, action }: Readonly<{ children: ReactNode; action?: ReactNode }>) {
  return (
    <div className="flex h-6 items-center justify-between gap-4">
      <h2 className="text-[13px] text-admin-muted">{children}</h2>
      {action}
    </div>
  )
}

/** A label on the left, its control on the right, like a sheet of properties. */
export function PropertyRow({
  label,
  htmlFor,
  align = "center",
  children
}: Readonly<{ label: string; htmlFor?: string; align?: "center" | "start"; children: ReactNode }>) {
  return (
    <div
      className={cn(
        "grid gap-2 py-3 sm:grid-cols-[136px_minmax(0,1fr)] sm:gap-4",
        align === "center" ? "sm:items-center" : "sm:items-start"
      )}
    >
      <label htmlFor={htmlFor} className={cn("text-[13px] text-admin-fg-2", align === "start" && "sm:pt-2")}>
        {label}
      </label>
      <div className="min-w-0">{children}</div>
    </div>
  )
}

export function Field({
  label,
  className,
  children
}: Readonly<{ label: string; className?: string; children: ReactNode }>) {
  return (
    <label className={cn("flex min-w-0 flex-col gap-2", className)}>
      <span className="text-[13px] leading-4 text-admin-fg-2">{label}</span>
      {children}
    </label>
  )
}

export function Switch({ className, ...props }: Omit<ComponentProps<"input">, "type" | "role">) {
  return (
    <span className={cn("relative inline-flex h-5 w-8 shrink-0", className)}>
      <input
        type="checkbox"
        role="switch"
        className="peer absolute inset-0 z-10 m-0 cursor-pointer opacity-0 disabled:cursor-default"
        {...props}
      />
      <span
        aria-hidden
        className={cn(
          "pointer-events-none flex h-5 w-8 items-center rounded-full bg-admin-fill-2 p-0.5",
          "transition-[background-color] duration-150 ease-admin",
          "peer-checked:bg-admin-primary peer-focus-visible:ring-2 peer-focus-visible:ring-admin-fg/20",
          "peer-checked:[&>span]:translate-x-3"
        )}
      >
        <span className="size-4 rounded-full bg-admin-panel shadow-[0_1px_2px_rgba(0,0,0,0.2)] transition-transform duration-150 ease-admin dark:bg-admin-primary-fg" />
      </span>
    </span>
  )
}

export function Segmented<T extends string>({
  value,
  options,
  onChange
}: Readonly<{ value: T; options: readonly { value: T; label: string }[]; onChange: (value: T) => void }>) {
  return (
    <div role="tablist" className="inline-flex w-fit self-start rounded-ctl bg-admin-fill-2 p-0.5">
      {options.map((option) => {
        const isActive = option.value === value
        return (
          <button
            key={option.value}
            type="button"
            role="tab"
            aria-selected={isActive}
            onClick={() => onChange(option.value)}
            className={cn(
              "h-7 min-w-24 rounded-tag px-3 text-[13px] transition-[background-color,color] duration-150 ease-admin",
              focusRing,
              isActive
                ? "bg-admin-panel text-admin-fg shadow-[0_1px_2px_rgba(0,0,0,0.08)]"
                : "text-admin-fg-2 hover:text-admin-fg"
            )}
          >
            {option.label}
          </button>
        )
      })}
    </div>
  )
}

export function Dropzone({
  icon: Icon,
  title,
  hint,
  isActive = false,
  className,
  children,
  ...props
}: Omit<ComponentProps<"label">, "title"> & {
  icon: LucideIcon
  title: string
  hint?: string
  isActive?: boolean
}) {
  return (
    <label
      className={cn(
        "flex h-14 min-w-0 cursor-pointer items-center gap-3 rounded-ctl border border-dashed px-3",
        "transition-[background-color,border-color] duration-150 ease-admin",
        "focus-within:border-admin-fg/40 focus-within:ring-[3px] focus-within:ring-admin-fg/[0.06]",
        isActive
          ? "border-admin-fg/40 bg-admin-fill"
          : "border-admin-line-strong bg-admin-panel hover:bg-admin-fill",
        className
      )}
      {...props}
    >
      {children}
      <span className="flex size-8 shrink-0 items-center justify-center rounded-tag bg-admin-fill-2 text-admin-fg-2">
        <Icon className="size-4" strokeWidth={1.5} />
      </span>
      <span className="flex min-w-0 flex-col">
        <span className="truncate text-[13px] text-admin-fg">{title}</span>
        {hint ? <span className="truncate text-xs text-admin-muted">{hint}</span> : null}
      </span>
    </label>
  )
}

export function StatusLine({ tone, children }: Readonly<{ tone: "neutral" | "error"; children: ReactNode }>) {
  return (
    <p
      role={tone === "error" ? "alert" : "status"}
      className={cn("text-[13px] leading-5", tone === "error" ? "text-admin-negative" : "text-admin-fg-2")}
    >
      {children}
    </p>
  )
}

export function EmptyState({ icon: Icon, title }: Readonly<{ icon: LucideIcon; title: string }>) {
  return (
    <div className="flex h-full min-h-64 flex-col items-center justify-center gap-3 text-center">
      <span className="flex size-10 items-center justify-center rounded-[10px] bg-admin-fill text-admin-muted">
        <Icon className="size-4" strokeWidth={1.5} />
      </span>
      <p className="text-sm text-admin-fg-2">{title}</p>
    </div>
  )
}

/** A green dot for what couples can see right now. */
export function LiveDot({ className }: Readonly<{ className?: string }>) {
  return <span aria-hidden className={cn("size-1.5 shrink-0 rounded-full bg-admin-positive", className)} />
}

export function PublishState({ published }: Readonly<{ published: boolean }>) {
  return (
    <span className="inline-flex shrink-0 items-center gap-1.5 text-xs text-admin-fg-2">
      {published ? (
        <LiveDot />
      ) : (
        <span aria-hidden className="size-1.5 shrink-0 rounded-full border border-admin-muted" />
      )}
      {published ? "Published" : "Draft"}
    </span>
  )
}

export function Spinner({ className }: Readonly<{ className?: string }>) {
  return <LoaderCircle aria-hidden className={cn("size-4 animate-spin", className)} strokeWidth={1.5} />
}
