import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { tap } from 'rxjs/operators';
import { BehaviorSubject, Observable } from 'rxjs';

export interface User {
    email: string;
    role: string;
    personUri: string | null;
}

export interface UserDto {
    email: string;
    role: string;
    personUri: string;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
    private userSubject = new BehaviorSubject<User | null>(null);
    user$ = this.userSubject.asObservable();

    constructor(private http: HttpClient) {
        const email = localStorage.getItem('email');
        const role = localStorage.getItem('role');
        const personUri = localStorage.getItem('personUri');
        if (email && role) {
            this.userSubject.next({ email, role, personUri });
        }
    }

    private get user(): User | null {
        return this.userSubject.getValue();
    }

    get email(): string | null { return this.user?.email ?? null; }
    get role(): string | null { return this.user?.role ?? null; }
    get personUri(): string | null { return this.user?.personUri ?? null; }

    get isAuthenticated(): boolean {
        return this.user !== null;
    }

    get displayName(): string | null {
        const e = this.email;
        if (!e) return null;
        return e.includes('@') ? e.split('@')[0] : e;
    }

    register(email: string, password: string): Observable<any> {
        return this.http.post('api/auth/register', { email, password });
    }

    login(email: string, password: string): Observable<any> {
        return this.http.post<{ role: string; email: string; personUri: string }>(
            'api/auth/login',
            { email, password }
        ).pipe(
            tap(res => {
                const personUri = res.personUri || null;
                localStorage.setItem('role', res.role);
                localStorage.setItem('email', res.email);
                if (personUri) {
                    localStorage.setItem('personUri', personUri);
                } else {
                    localStorage.removeItem('personUri');
                }
                this.userSubject.next({ email: res.email, role: res.role, personUri });
            })
        );
    }

    logout(): Observable<any> {
        return this.http.post('api/auth/logout', {}).pipe(
            tap(() => {
                localStorage.removeItem('role');
                localStorage.removeItem('email');
                localStorage.removeItem('personUri');
                this.userSubject.next(null);
            })
        );
    }

    linkPersonUri(personUri: string): Observable<any> {
        return this.http.patch('api/auth/me/person', { email: this.email, personUri }).pipe(
            tap(() => {
                localStorage.setItem('personUri', personUri);
                const current = this.user;
                if (current) {
                    this.userSubject.next({ ...current, personUri });
                }
            })
        );
    }

    canEdit(classUri: string, instanceUri?: string): boolean {
        if (this.role === 'ADMIN') return true;
        if (!classUri.includes('Person')) return false;
        return !!instanceUri && instanceUri === this.personUri;
    }

    // ==================== Admin: User Management ====================

    getUsers(): Observable<UserDto[]> {
        return this.http.get<UserDto[]>('api/auth/users');
    }

    createUser(email: string, password: string, role: string): Observable<any> {
        return this.http.post<any>('api/auth/users', { email, password, role });
    }

    updateUserRole(email: string, role: string): Observable<any> {
        return this.http.patch<any>(`api/auth/users/${encodeURIComponent(email)}`, { role });
    }

    resetPassword(email: string, password: string): Observable<any> {
        return this.http.post<any>(`api/auth/users/${encodeURIComponent(email)}/reset-password`, { password });
    }

    deleteUser(email: string): Observable<any> {
        return this.http.delete<any>(`api/auth/users/${encodeURIComponent(email)}`);
    }
}
